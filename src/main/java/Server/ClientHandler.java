package Server;

import Common.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * İstemci bağlantısını handle eden sınıf
 * Robust OT entegreli DocumentManager ile çalışır
 */
public class ClientHandler implements Runnable {

    // Temel bağlantı bilgileri
    private final Server server;
    private final Socket clientSocket;
    private String tempClientId;
    private String userId = null;
    private String username = null;

    // Stream'ler
    private BufferedReader reader;
    private PrintWriter writer;

    // Durum yönetimi
    private final AtomicBoolean isConnected = new AtomicBoolean(true);
    private final AtomicBoolean isAuthenticated = new AtomicBoolean(false);

    // Açık dosyalar
    private final Set<String> openFiles = Collections.synchronizedSet(new HashSet<>());

    // İstatistikler
    private final long connectionTime;
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

            // Socket timeout
            clientSocket.setSoTimeout(Protocol.CONNECTION_TIMEOUT);

        } catch (IOException e) {
            Protocol.logError("ClientHandler oluşturma hatası: " + tempClientId, e);
            disconnect();
        }
    }

    /**
     * Ana thread metodu - mesaj işleme loop'u
     */
    @Override
    public void run() {
        Protocol.log("ClientHandler başlatıldı: " + tempClientId);

        try {
            // Ana mesaj işleme loop'u
            while (isConnected.get() && !clientSocket.isClosed()) {
                try {
                    // İstemciden mesaj oku
                    String rawMessage = Utils.readFromSocket(clientSocket);

                    if (rawMessage != null && !rawMessage.trim().isEmpty()) {
                        messagesReceived++;
                        processMessage(rawMessage);
                    } else {
                        // Null mesaj - bağlantı kopmuş olabilir
                        break;
                    }

                } catch (SocketTimeoutException e) {
                    // Timeout - heartbeat kontrolü (basit versiyon)
                    if (isAuthenticated.get()) {
                        // Heartbeat gönderebiliriz (opsiyonel)
                    }

                } catch (IOException e) {
                    Protocol.log("İstemci bağlantı hatası: " + getUserId() + " - " + e.getMessage());
                    break;
                }
            }

        } catch (Exception e) {
            Protocol.logError("ClientHandler beklenmeyen hata: " + getUserId(), e);
        } finally {
            disconnect();
        }
    }

    /**
     * Gelen mesajı parse eder ve işler
     */
    private void processMessage(String rawMessage) {
        try {
            Message message = Message.deserialize(rawMessage);
            Protocol.log("Mesaj alındı: " + message.getType() + " from " + getUserId());

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

                case SAVE:
                    handleSave(message);
                    break;

                default:
                    sendError("Desteklenmeyen mesaj tipi: " + message.getType());
                    break;
            }

        } catch (Exception e) {
            Protocol.logError("Mesaj parse hatası: " + getUserId(), e);
            sendError("Geçersiz mesaj formatı");
        }
    }

    // === MESSAGE HANDLERS ===

    /**
     * CONNECT mesajını işle
     */
    private void handleConnect(Message message) {
        if (isAuthenticated.get()) {
            sendError("Zaten bağlısınız");
            return;
        }

        String username = message.getData("username");

        if (!Protocol.isValidUsername(username)) {
            sendError("Geçersiz kullanıcı adı");
            return;
        }

        // Unique user ID oluştur
        String newUserId = Protocol.generateUserId();

        // Server'a kaydet
        if (server.registerClient(newUserId, this)) {
            this.userId = newUserId;
            this.username = username;
            isAuthenticated.set(true);

            // Başarılı bağlantı
            Message response = Message.createConnectAck(userId, true);
            sendMessage(response);

            Protocol.log("Kullanıcı bağlandı: " + username + " (" + userId + ")");

        } else {
            sendError("Bağlantı hatası");
        }
    }

    /**
     * DISCONNECT mesajını işle
     */
    private void handleDisconnect(Message message) {
        Protocol.log("Kullanıcı disconnect istedi: " + getUserId());
        disconnect();
    }

    /**
     * FILE_LIST mesajını işle
     */
    private void handleFileList(Message message) {
        if (!checkAuthenticated()) return;

        try {
            List<DocumentManager.DocumentInfo> files =
                    server.getDocumentManager().getAllDocuments();

            // JSON formatında dosya listesi
            StringBuilder fileListData = new StringBuilder();

            for (int i = 0; i < files.size(); i++) {
                DocumentManager.DocumentInfo file = files.get(i);
                if (i > 0) fileListData.append(",");

                fileListData.append(file.getFileId()).append(":").append(file.getFileName())
                        .append(":").append(file.getUserCount());
            }

            Message response = new Message(Message.MessageType.FILE_LIST_RESP, userId, null)
                    .addData("files", fileListData.toString());
            sendMessage(response);

        } catch (Exception e) {
            Protocol.logError("Dosya listesi oluşturma hatası: " + getUserId(), e);
            sendError("Dosya listesi alınamadı");
        }
    }

    /**
     * FILE_CREATE mesajını işle
     */
    private void handleFileCreate(Message message) {
        if (!checkAuthenticated()) return;

        String fileName = message.getData("name");

        if (!Protocol.isValidFilename(fileName)) {
            sendError("Geçersiz dosya ismi");
            return;
        }

        try {
            String fileId = server.getDocumentManager().createDocument(fileName, userId);

            if (fileId != null) {
                // Dosyayı otomatik aç
                openFiles.add(fileId);

                // Başarılı oluşturma response'u
                Message response = Message.createFileContent(userId, fileId, "")
                        .addData("name", fileName);
                sendMessage(response);

                Protocol.log("Dosya oluşturuldu: " + fileName + " (" + fileId + ") by " + getUserId());

            } else {
                sendError("Dosya oluşturulamadı");
            }

        } catch (Exception e) {
            Protocol.logError("Dosya oluşturma hatası: " + getUserId(), e);
            sendError("Dosya oluşturma hatası");
        }
    }

    /**
     * FILE_OPEN mesajını işle
     */
    private void handleFileOpen(Message message) {
        if (!checkAuthenticated()) return;

        String fileId = message.getFileId();

        if (fileId == null) {
            sendError("Dosya ID gerekli");
            return;
        }

        try {
            DocumentManager.Document doc =
                    server.getDocumentManager().openDocument(fileId, userId);

            if (doc != null) {
                // Dosyayı açık listesine ekle
                openFiles.add(fileId);

                // Dosya içeriğini gönder
                List<String> currentUsers = server.getDocumentManager().getFileUsers(fileId);
                String usersData = String.join(",", currentUsers);

                Message response = Message.createFileContent(userId, fileId, doc.getContent())
                        .addData("users", usersData);
                sendMessage(response);

                Protocol.log("Dosya açıldı: " + fileId + " by " + getUserId());

            } else {
                sendError("Dosya bulunamadı");
            }

        } catch (Exception e) {
            Protocol.logError("Dosya açma hatası: " + getUserId(), e);
            sendError("Dosya açma hatası");
        }
    }

    /**
     * TEXT_INSERT mesajını işle
     */
    private void handleTextInsert(Message message) {
        if (!checkAuthenticated()) return;

        String fileId = message.getFileId();
        if (!isFileOpen(fileId)) {
            sendError("Dosya açık değil");
            return;
        }

        try {
            Integer position = message.getDataAsInt("position");
            String text = message.getData("text");

            if (position == null || text == null) {
                sendError("Position ve text gerekli");
                return;
            }

            // DocumentManager'da Robust OT ile insert
            boolean success = server.getDocumentManager()
                    .insertText(fileId, position, text, userId);

            if (success) {
                // Diğer kullanıcılara broadcast (OT sonrası pozisyon)
                Message updateMsg = Message.createTextUpdate(userId, fileId, "insert", position, text);
                server.broadcastToFile(fileId, updateMsg, userId);

                Protocol.log("Text insert: " + fileId + " pos:" + position + " by " + getUserId());

            } else {
                sendError("Metin eklenemedi");
            }

        } catch (Exception e) {
            Protocol.logError("Text insert hatası: " + getUserId(), e);
            sendError("Metin ekleme hatası");
        }
    }

    /**
     * TEXT_DELETE mesajını işle
     */
    private void handleTextDelete(Message message) {
        if (!checkAuthenticated()) return;

        String fileId = message.getFileId();
        if (!isFileOpen(fileId)) {
            sendError("Dosya açık değil");
            return;
        }

        try {
            Integer position = message.getDataAsInt("position");
            Integer length = message.getDataAsInt("length");

            if (position == null || length == null) {
                sendError("Position ve length gerekli");
                return;
            }

            // DocumentManager'da Robust OT ile delete
            boolean success = server.getDocumentManager()
                    .deleteText(fileId, position, length, userId);

            if (success) {
                // Diğer kullanıcılara broadcast
                Message updateMsg = Message.createTextUpdate(userId, fileId, "delete", position, "")
                        .addData("length", length);
                server.broadcastToFile(fileId, updateMsg, userId);

                Protocol.log("Text delete: " + fileId + " pos:" + position + " len:" + length + " by " + getUserId());

            } else {
                sendError("Metin silinemedi");
            }

        } catch (Exception e) {
            Protocol.logError("Text delete hatası: " + getUserId(), e);
            sendError("Metin silme hatası");
        }
    }

    /**
     * SAVE mesajını işle
     */
    private void handleSave(Message message) {
        if (!checkAuthenticated()) return;

        String fileId = message.getFileId();
        if (!isFileOpen(fileId)) {
            sendError("Dosya açık değil");
            return;
        }

        try {
            boolean success = server.getDocumentManager().saveDocument(fileId);

            Message response = new Message(Message.MessageType.SAVE, userId, fileId)
                    .addData("status", success ? "success" : "fail");
            sendMessage(response);

            if (success) {
                Protocol.log("Dosya kaydedildi: " + fileId + " by " + getUserId());
            }

        } catch (Exception e) {
            Protocol.logError("Save hatası: " + getUserId(), e);
            sendError("Kaydetme hatası");
        }
    }

    // === UTILITY METHODS ===

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
            Protocol.logError("Mesaj gönderme hatası: " + getUserId(), e);
            disconnect();
        }
    }

    /**
     * Hata mesajı gönder
     */
    private void sendError(String errorMessage) {
        Message errorMsg = Message.createError(userId, errorMessage);
        sendMessage(errorMsg);
    }

    /**
     * Authentication kontrolü
     */
    private boolean checkAuthenticated() {
        if (!isAuthenticated.get()) {
            sendError("Önce giriş yapmalısınız");
            return false;
        }
        return true;
    }

    /**
     * Dosyanın açık olup olmadığını kontrol et
     */
    private boolean isFileOpen(String fileId) {
        return fileId != null && openFiles.contains(fileId);
    }

    /**
     * Bağlantıyı kapat
     */
    public void disconnect() {
        if (!isConnected.compareAndSet(true, false)) {
            return; // Zaten kapatılmış
        }

        Protocol.log("İstemci bağlantısı kapatılıyor: " + getUserId());

        try {
            // Açık dosyalardan çık
            synchronized (openFiles) {
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
            if (clientSocket != null && !clientSocket.isClosed()) {
                clientSocket.close();
            }

        } catch (Exception e) {
            Protocol.logError("Disconnect hatası: " + getUserId(), e);
        }
    }

    // === GETTER METHODS ===

    /**
     * User ID döndür (authenticated ise gerçek ID, değilse temp ID)
     */
    public String getUserId() {
        return userId != null ? userId : tempClientId;
    }

    /**
     * Username döndür
     */
    public String getUsername() {
        return username;
    }

    /**
     * Authentication durumu
     */
    public boolean isAuthenticated() {
        return isAuthenticated.get();
    }

    /**
     * Bağlantı durumu
     */
    public boolean isConnected() {
        return isConnected.get();
    }

    /**
     * Gönderilen mesaj sayısı
     */
    public int getMessagesSent() {
        return messagesSent;
    }

    /**
     * Alınan mesaj sayısı
     */
    public int getMessagesReceived() {
        return messagesReceived;
    }

    /**
     * Bağlantı zamanı
     */
    public long getConnectionTime() {
        return connectionTime;
    }

    /**
     * Açık dosya listesi
     */
    public Set<String> getOpenFiles() {
        synchronized (openFiles) {
            return new HashSet<>(openFiles);
        }
    }

    /**
     * Bağlantı süresi (milisaniye)
     */
    public long getConnectionDuration() {
        return System.currentTimeMillis() - connectionTime;
    }

    /**
     * İstemci bilgileri (debug için)
     */
    @Override
    public String toString() {
        return String.format("ClientHandler{userId='%s', username='%s', connected=%s, authenticated=%s, openFiles=%d}",
                getUserId(), username, isConnected.get(), isAuthenticated.get(), openFiles.size());
    }
}