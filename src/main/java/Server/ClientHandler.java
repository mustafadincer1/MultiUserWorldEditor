package Server;

import Common.Message;
import Common.Protocol;
import Common.Utils;
import Exception.MessageParseException;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Her istemci bağlantısı için ayrı thread'de çalışan handler sınıfı
 * Protokol mesajlarını işler ve response'ları gönderir
 */
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Her istemci bağlantısı için ayrı thread'de çalışan handler sınıfı
 * Protokol mesajlarını işler ve response'ları gönderir
 */
public class ClientHandler implements Runnable {

    // Temel bağlantı bilgileri
    private final Server server;
    private final Socket clientSocket;
    private String tempClientId; // İlk bağlantıda geçici ID
    private String userId = null; // CONNECT sonrası gerçek user ID
    private String username = null;

    // Stream'ler
    private BufferedReader reader;
    private PrintWriter writer;

    // Durum yönetimi
    private final AtomicBoolean isConnected = new AtomicBoolean(true);
    private final AtomicBoolean isAuthenticated = new AtomicBoolean(false);

    // Açık dosyalar (bu kullanıcının açtığı dosyalar)
    private final Set<String> openFiles = new HashSet<>();
    private final Object fileLock = new Object();

    // İstatistikler
    private long connectionTime;
    private int messagesSent = 0;
    private int messagesReceived = 0;

    /**
     * Constructor
     */
    public ClientHandler(Server server, Socket clientSocket, String tempClientId) {
        this.server = server;
        this.clientSocket = clientSocket;
        this.tempClientId = tempClientId;
        this.connectionTime = System.currentTimeMillis();

        try {
            // Input/Output stream'leri hazırla
            this.reader = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream(), "UTF-8"));
            this.writer = new PrintWriter(
                    new OutputStreamWriter(clientSocket.getOutputStream(), "UTF-8"), true);

            // Socket timeout ayarla
            clientSocket.setSoTimeout(Protocol.CLIENT_TIMEOUT);

        } catch (IOException e) {
            Utils.logError("ClientHandler oluşturma hatası: " + tempClientId, e);
            disconnect();
        }
    }

    /**
     * Ana thread metodu - mesajları dinler ve işler
     */
    @Override
    public void run() {
        Utils.log("ClientHandler başlatıldı: " + tempClientId);

        try {
            // Ana mesaj işleme loop'u
            while (isConnected.get() && !clientSocket.isClosed()) {
                try {
                    // İstemciden mesaj oku
                    System.out.println("DEBUG: Mesaj okunmaya çalışılıyor..."); // DEBUG
                    String rawMessage = Utils.readFromSocket(clientSocket);
                    System.out.println("DEBUG: Okunan mesaj: '" + rawMessage + "'"); // DEBUG

                    if (rawMessage != null && !rawMessage.trim().isEmpty()) {
                        messagesReceived++;
                        processMessage(rawMessage);
                    } else {
                        System.out.println("DEBUG: Mesaj null veya boş"); // DEBUG
                    }

                } catch (SocketTimeoutException e) {
                    // Timeout - heartbeat kontrolü yapılabilir
                    if (isAuthenticated.get()) {
                        sendHeartbeat();
                    }

                } catch (IOException e) {
                    Utils.log("İstemci bağlantı hatası: " + getUserId() + " - " + e.getMessage());
                    break;
                }
            }

        } catch (Exception e) {
            Utils.logError("ClientHandler beklenmeyen hata: " + getUserId(), e);
        } finally {
            disconnect();
        }
    }

    /**
     * Gelen mesajı parse eder ve uygun metotu çağırır
     */
    private void processMessage(String rawMessage) {
        try {
            Message message = Message.deserialize(rawMessage);
            Utils.log("Mesaj alındı: " + message.getType() + " from " + getUserId());

            // Mesaj tipine göre işle
            switch (message.getType()) {
                case CONNECT:
                    handleConnect(message);
                    break;

                case DISCONNECT:
                    handleDisconnect(message);
                    break;

                case FILE_LIST:
                    handleFileList(message);
                    break;

                case FILE_CREATE:
                    handleFileCreate(message);
                    break;

                case FILE_OPEN:
                    handleFileOpen(message);
                    break;

                case TEXT_INSERT:
                    handleTextInsert(message);
                    break;

                case TEXT_DELETE:
                    handleTextDelete(message);
                    break;

                case CURSOR_MOVE:
                    handleCursorMove(message);
                    break;

                case SAVE:
                    handleSave(message);
                    break;

                default:
                    sendError(Protocol.ERROR_INVALID_FORMAT,
                            "Desteklenmeyen mesaj tipi: " + message.getType());
                    break;
            }

        } catch (MessageParseException e) {
            Utils.logError("Mesaj parse hatası: " + getUserId(), e);
            sendError(Protocol.ERROR_INVALID_FORMAT, "Geçersiz mesaj formatı");
        }
    }

    /**
     * CONNECT mesajını işler - kullanıcı authentication
     */
    private void handleConnect(Message message) {
        if (isAuthenticated.get()) {
            sendError(Protocol.ERROR_USER_ALREADY_CONNECTED, "Zaten bağlısınız");
            return;
        }

        String username = message.getDataValue("username");

        // Username validation
        if (!Protocol.isValidUsername(username)) {
            sendError(Protocol.ERROR_INVALID_USERNAME, "Geçersiz kullanıcı adı");
            return;
        }

        // Unique user ID oluştur
        String newUserId = Protocol.generateUserId();

        // Server'a kaydet
        if (server.registerClient(newUserId, this)) {
            this.userId = newUserId;
            this.username = username;
            isAuthenticated.set(true);

            // Başarılı bağlantı response'u
            Message response = Message.createConnectAckMessage(userId, true);
            sendMessage(response);

            Utils.log("Kullanıcı başarıyla bağlandı: " + username + " (" + userId + ")");

        } else {
            sendError(Protocol.ERROR_USER_ALREADY_CONNECTED, "Kullanıcı zaten bağlı");
        }
    }

    /**
     * DISCONNECT mesajını işler
     */
    private void handleDisconnect(Message message) {
        Utils.log("Kullanıcı disconnect istedi: " + getUserId());
        disconnect();
    }

    /**
     * FILE_LIST mesajını işler - mevcut dosyaları listeler
     */
    private void handleFileList(Message message) {
        if (!checkAuthenticated()) return;

        try {
            List<DocumentManager.DocumentInfo> files = server.getDocumentManager().getAllDocuments();

            // JSON formatında dosya listesi oluştur
            StringBuilder fileListJson = new StringBuilder("{\"files\":[");

            for (int i = 0; i < files.size(); i++) {
                DocumentManager.DocumentInfo file = files.get(i);
                if (i > 0) fileListJson.append(",");

                fileListJson.append(String.format(
                        "{\"id\":\"%s\",\"name\":\"%s\",\"users\":%d}",
                        file.getFileId(),
                        file.getFileName(),
                        file.getUserCount()
                ));
            }

            fileListJson.append("]}");

            Message response = new Message(Message.MessageType.FILE_LIST_RESP,
                    userId, null, fileListJson.toString());
            sendMessage(response);

        } catch (Exception e) {
            Utils.logError("Dosya listesi oluşturma hatası: " + getUserId(), e);
            sendError(Protocol.ERROR_GENERAL, "Dosya listesi alınamadı");
        }
    }

    /**
     * FILE_CREATE mesajını işler - yeni dosya oluşturur
     */
    private void handleFileCreate(Message message) {
        if (!checkAuthenticated()) return;

        String fileName = message.getDataValue("name");

        if (!Protocol.isValidFilename(fileName)) {
            sendError(Protocol.ERROR_INVALID_FORMAT, "Geçersiz dosya ismi");
            return;
        }

        try {
            String fileId = server.getDocumentManager().createDocument(fileName, userId);

            if (fileId != null) {
                // Dosyayı otomatik olarak aç
                synchronized (fileLock) {
                    openFiles.add(fileId);
                }

                // Başarılı oluşturma response'u
                String responseData = String.format("{\"fileId\":\"%s\",\"name\":\"%s\"}",
                        fileId, fileName);
                Message response = new Message(Message.MessageType.FILE_CONTENT,
                        userId, fileId, responseData);
                sendMessage(response);

                Utils.log("Dosya oluşturuldu: " + fileName + " (" + fileId + ") by " + getUserId());

            } else {
                sendError(Protocol.ERROR_GENERAL, "Dosya oluşturulamadı");
            }

        } catch (Exception e) {
            Utils.logError("Dosya oluşturma hatası: " + getUserId(), e);
            sendError(Protocol.ERROR_GENERAL, "Dosya oluşturma hatası");
        }
    }

    /**
     * FILE_OPEN mesajını işler - mevcut dosyayı açar
     */
    private void handleFileOpen(Message message) {
        if (!checkAuthenticated()) return;

        String fileId = message.getDataValue("fileId");

        if (fileId == null) {
            sendError(Protocol.ERROR_INVALID_FORMAT, "Dosya ID gerekli");
            return;
        }

        try {
            DocumentManager.Document doc = server.getDocumentManager().openDocument(fileId, userId);

            if (doc != null) {
                // Dosyayı açık dosyalar listesine ekle
                synchronized (fileLock) {
                    openFiles.add(fileId);
                }

                // Dosya içeriğini gönder
                List<String> currentUsers = server.getDocumentManager().getFileUsers(fileId);
                StringBuilder usersJson = new StringBuilder("[");
                for (int i = 0; i < currentUsers.size(); i++) {
                    if (i > 0) usersJson.append(",");
                    usersJson.append("\"").append(currentUsers.get(i)).append("\"");
                }
                usersJson.append("]");

                String responseData = String.format(
                        "{\"content\":\"%s\",\"users\":%s}",
                        Utils.escapeJson(doc.getContent()),
                        usersJson.toString()
                );

                Message response = new Message(Message.MessageType.FILE_CONTENT,
                        userId, fileId, responseData);
                sendMessage(response);

                Utils.log("Dosya açıldı: " + fileId + " by " + getUserId());

            } else {
                sendError(Protocol.ERROR_FILE_NOT_FOUND, "Dosya bulunamadı");
            }

        } catch (Exception e) {
            Utils.logError("Dosya açma hatası: " + getUserId(), e);
            sendError(Protocol.ERROR_GENERAL, "Dosya açma hatası");
        }
    }

    /**
     * TEXT_INSERT mesajını işler - metne ekleme yapar
     */
    private void handleTextInsert(Message message) {
        if (!checkAuthenticated()) return;

        String fileId = message.getFileId();
        if (!isFileOpen(fileId)) {
            sendError(Protocol.ERROR_UNAUTHORIZED, "Dosya açık değil");
            return;
        }

        try {
            Integer position = message.getDataValueAsInt("position");
            String text = message.getDataValue("text");

            if (position == null || text == null) {
                sendError(Protocol.ERROR_INVALID_FORMAT, "Position ve text gerekli");
                return;
            }

            // Document manager'da güncelleme yap
            boolean success = server.getDocumentManager().insertText(fileId, position, text, userId);

            if (success) {
                // Diğer kullanıcılara broadcast et
                Message updateMsg = new Message(Message.MessageType.TEXT_UPDATE, userId, fileId,
                        String.format("{\"userId\":\"%s\",\"position\":\"%d\",\"text\":\"%s\",\"operation\":\"insert\"}",
                                userId, position, Utils.escapeJson(text)));

                server.broadcastToFile(fileId, updateMsg, userId);

                Utils.log("Text insert: " + fileId + " pos:" + position + " by " + getUserId());

            } else {
                sendError(Protocol.ERROR_GENERAL, "Metin eklenemedi");
            }

        } catch (Exception e) {
            Utils.logError("Text insert hatası: " + getUserId(), e);
            sendError(Protocol.ERROR_GENERAL, "Metin ekleme hatası");
        }
    }

    /**
     * TEXT_DELETE mesajını işler - metinden silme yapar
     */
    private void handleTextDelete(Message message) {
        if (!checkAuthenticated()) return;

        String fileId = message.getFileId();
        if (!isFileOpen(fileId)) {
            sendError(Protocol.ERROR_UNAUTHORIZED, "Dosya açık değil");
            return;
        }

        try {
            Integer position = message.getDataValueAsInt("position");
            Integer length = message.getDataValueAsInt("length");

            if (position == null || length == null) {
                sendError(Protocol.ERROR_INVALID_FORMAT, "Position ve length gerekli");
                return;
            }

            // Document manager'da silme yap
            boolean success = server.getDocumentManager().deleteText(fileId, position, length, userId);

            if (success) {
                // Diğer kullanıcılara broadcast et
                Message updateMsg = new Message(Message.MessageType.TEXT_UPDATE, userId, fileId,
                        String.format("{\"userId\":\"%s\",\"position\":\"%d\",\"length\":\"%d\",\"operation\":\"delete\"}",
                                userId, position, length));

                server.broadcastToFile(fileId, updateMsg, userId);

                Utils.log("Text delete: " + fileId + " pos:" + position + " len:" + length + " by " + getUserId());

            } else {
                sendError(Protocol.ERROR_GENERAL, "Metin silinemedi");
            }

        } catch (Exception e) {
            Utils.logError("Text delete hatası: " + getUserId(), e);
            sendError(Protocol.ERROR_GENERAL, "Metin silme hatası");
        }
    }

    /**
     * CURSOR_MOVE mesajını işler
     */
    private void handleCursorMove(Message message) {
        if (!checkAuthenticated()) return;

        String fileId = message.getFileId();
        if (!isFileOpen(fileId)) {
            return; // Cursor move için hata gönderme
        }

        try {
            Integer position = message.getDataValueAsInt("position");

            if (position != null) {
                // Diğer kullanıcılara cursor pozisyonunu bildir
                Message cursorMsg = new Message(Message.MessageType.CURSOR_UPDATE, userId, fileId,
                        String.format("{\"userId\":\"%s\",\"position\":\"%d\"}", userId, position));

                server.broadcastToFile(fileId, cursorMsg, userId);
            }

        } catch (Exception e) {
            Utils.logError("Cursor move hatası: " + getUserId(), e);
        }
    }

    /**
     * SAVE mesajını işler - dosyayı kaydeder
     */
    private void handleSave(Message message) {
        if (!checkAuthenticated()) return;

        String fileId = message.getFileId();
        if (!isFileOpen(fileId)) {
            sendError(Protocol.ERROR_UNAUTHORIZED, "Dosya açık değil");
            return;
        }

        try {
            boolean success = server.getDocumentManager().saveDocument(fileId);

            Message response = new Message(Message.MessageType.SAVE_ACK, userId, fileId,
                    String.format("{\"status\":\"%s\"}", success ? "success" : "fail"));

            sendMessage(response);

            Utils.log("Dosya kaydedildi: " + fileId + " by " + getUserId());

        } catch (Exception e) {
            Utils.logError("Save hatası: " + getUserId(), e);
            sendError(Protocol.ERROR_GENERAL, "Kaydetme hatası");
        }
    }

    /**
     * Heartbeat mesajı gönder
     */
    private void sendHeartbeat() {
        // Basit heartbeat implementasyonu
        // Gerekirse özel heartbeat mesajı eklenebilir
    }

    /**
     * İstemciye mesaj gönder
     */
    public void sendMessage(Message message) {
        if (!isConnected.get() || clientSocket.isClosed()) {
            return;
        }

        try {
            String serialized = message.serialize();
            Utils.writeToSocket(clientSocket, serialized);
            messagesSent++;

        } catch (IOException e) {
            Utils.logError("Mesaj gönderme hatası: " + getUserId(), e);
            disconnect();
        }
    }

    /**
     * Hata mesajı gönder
     */
    private void sendError(int errorCode, String errorMessage) {
        Message errorMsg = Message.createErrorMessage(userId, errorCode, errorMessage);
        sendMessage(errorMsg);
    }

    /**
     * Authentication kontrolü
     */
    private boolean checkAuthenticated() {
        if (!isAuthenticated.get()) {
            sendError(Protocol.ERROR_UNAUTHORIZED, "Önce giriş yapmalısınız");
            return false;
        }
        return true;
    }

    /**
     * Dosyanın açık olup olmadığını kontrol et
     */
    private boolean isFileOpen(String fileId) {
        synchronized (fileLock) {
            return fileId != null && openFiles.contains(fileId);
        }
    }

    /**
     * Bağlantıyı kapat
     */
    public void disconnect() {
        if (!isConnected.compareAndSet(true, false)) {
            return; // Zaten kapatılmış
        }

        Utils.log("İstemci bağlantısı kapatılıyor: " + getUserId());

        try {
            // Açık dosyalardan çık
            synchronized (fileLock) {
                for (String fileId : openFiles) {
                    server.getDocumentManager().closeDocument(fileId, userId);
                }
                openFiles.clear();
            }

            // Server'dan kayıt sil
            if (userId != null) {
                server.unregisterClient(userId);
            }

            // Socket'i kapat
            Utils.closeSocket(clientSocket);

        } catch (Exception e) {
            Utils.logError("Disconnect hatası: " + getUserId(), e);
        }
    }

    /**
     * Getter metotları
     */
    public String getUserId() {
        return userId != null ? userId : tempClientId;
    }

    public String getUsername() {
        return username;
    }

    public boolean isAuthenticated() {
        return isAuthenticated.get();
    }

    public boolean isConnected() {
        return isConnected.get();
    }

    public int getMessagesSent() {
        return messagesSent;
    }

    public int getMessagesReceived() {
        return messagesReceived;
    }

    public long getConnectionTime() {
        return connectionTime;
    }

    public Set<String> getOpenFiles() {
        synchronized (fileLock) {
            return new HashSet<>(openFiles);
        }
    }
}
