package Server;

import Common.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * İstemci bağlantısını handle eden sınıf
 * Robust OT entegreli DocumentManager ve UserManager ile çalışır
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
                case REGISTER:
                    handleRegister(message);
                    break;

                case LOGIN:
                    handleLogin(message);
                    break;

                case CONNECT:
                    // Eski sistem - artık desteklenmiyor
                    sendError("CONNECT komutu desteklenmiyor, LOGIN kullanın");
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
     * REGISTER mesajını işle
     */
    private void handleRegister(Message message) {
        if (isAuthenticated.get()) {
            sendError("Zaten giriş yapmışsınız");
            return;
        }

        String username = message.getData("username");
        String password = message.getData("password");

        if (username == null || password == null) {
            sendError("Kullanıcı adı ve şifre gerekli");
            return;
        }

        UserManager.RegisterResult result = server.getUserManager().registerUser(username, password);

        Message response = Message.createRegisterAck(result.success, result.message);
        sendMessage(response);

        if (result.success) {
            Protocol.log("Yeni kullanıcı kaydı: " + username + " from " + getTempClientId());
        } else {
            Protocol.log("Kayıt başarısız: " + username + " - " + result.message);
        }
    }

    /**
     * LOGIN mesajını işle
     */
    private void handleLogin(Message message) {
        if (isAuthenticated.get()) {
            sendError("Zaten giriş yapmışsınız");
            return;
        }

        String username = message.getData("username");
        String password = message.getData("password");

        if (username == null || password == null) {
            sendError("Kullanıcı adı ve şifre gerekli");
            return;
        }

        UserManager.LoginResult result = server.getUserManager().loginUser(username, password);

        if (result.success) {
            // Başarılı giriş
            this.userId = result.userId;
            this.username = username;
            isAuthenticated.set(true);

            // Server'a kaydet
            if (server.registerClient(userId, this)) {
                Protocol.log("Kullanıcı giriş yaptı: " + username + " (" + userId + ")");
            } else {
                // Server kayıt hatası - session'ı temizle
                server.getUserManager().logoutUser(userId);
                this.userId = null;
                this.username = null;
                isAuthenticated.set(false);

                Message response = Message.createLoginAck(null, false, "Server kayıt hatası");
                sendMessage(response);
                return;
            }
        } else {
            Protocol.log("Giriş başarısız: " + username + " - " + result.message);
        }

        Message response = Message.createLoginAck(result.userId, result.success, result.message);
        sendMessage(response);
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

            Protocol.log("DEBUG: handleFileList - Bulunan dosya sayısı: " + files.size());

            // GÜVENLI FORMAT: Pipe (|) separator kullan
            StringBuilder fileListData = new StringBuilder();

            for (int i = 0; i < files.size(); i++) {
                DocumentManager.DocumentInfo file = files.get(i);
                if (i > 0) fileListData.append("|");  // Virgül yerine pipe

                String fileEntry = file.getFileId() + ":" + file.getFileName() + ":" + file.getUserCount();
                fileListData.append(fileEntry);

                Protocol.log("DEBUG: Dosya " + (i+1) + "/" + files.size() + ": " + fileEntry);
            }

            String finalData = fileListData.toString();
            Protocol.log("DEBUG: Gönderilecek files data (pipe format): '" + finalData + "'");

            Message response = new Message(Message.MessageType.FILE_LIST_RESP, userId, null)
                    .addData("files", finalData);
            sendMessage(response);

            Protocol.log("DEBUG: FILE_LIST_RESP gönderildi - user: " + getUserId());

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
        Protocol.log("Gelen dosya adı: '" + fileName + "'");
        Protocol.log("Dosya adı byte'ları: " + Arrays.toString(fileName.getBytes()));
        Protocol.log("Validation sonucu: " + Protocol.isValidFilename(fileName));
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

    private void handleFileOpen(Message message) {
        if (!checkAuthenticated()) return;

        String fileId = message.getFileId();
        Protocol.log("DEBUG: handleFileOpen - gelen fileId: '" + fileId + "'");

        if (fileId == null || fileId.trim().isEmpty()) {
            Protocol.log("ERROR: handleFileOpen - fileId null veya boş");
            sendError("Dosya ID geçersiz");
            return;
        }

        try {
            // Client hatalı olarak "fileId:fileName:userCount" formatında gönderiyor
            // Sadece fileId kısmını al
            String actualFileId = fileId;
            if (fileId.contains(":")) {
                String[] parts = fileId.split(":");
                if (parts.length >= 1) {
                    actualFileId = parts[0].trim();
                    Protocol.log("DEBUG: Client hatası düzeltildi. Gerçek fileId: '" + actualFileId + "'");
                }
            }

            // ❌ BU SATIRI KALDIR - FileId için sanitize gerekli değil
            // String cleanFileId = Utils.sanitizeFileName(actualFileId.trim());

            // ✅ BU ŞEKILDE DEĞİŞTİR - Sadece trim yap
            String cleanFileId = actualFileId.trim();

            if (cleanFileId == null || cleanFileId.isEmpty()) {
                Protocol.log("ERROR: cleanFileId boş - input: '" + actualFileId + "'");
                sendError("Geçersiz dosya ID: " + fileId);
                return;
            }

            Protocol.log("DEBUG: İşlenecek dosya ID: '" + cleanFileId + "'");

            // ====== CRITICAL DEBUG EKLE ======
            Protocol.log("=== FILE_OPEN DEBUG ===");
            Protocol.log("DEBUG: Before adding to openFiles - userId: " + userId);
            Protocol.log("DEBUG: cleanFileId: '" + cleanFileId + "'");
            Protocol.log("DEBUG: openFiles before: " + openFiles);

            // DocumentManager'dan openDocument çağır
            DocumentManager.Document doc = server.getDocumentManager().openDocument(cleanFileId, userId);

            if (doc != null) {
                // Dosyayı açık listesine ekle
                openFiles.add(cleanFileId);

                Protocol.log("DEBUG: openFiles after: " + openFiles);
                Protocol.log("DEBUG: openFiles.contains(cleanFileId): " + openFiles.contains(cleanFileId));
                Protocol.log("DEBUG: openFiles size: " + openFiles.size());
                Protocol.log("======================");

                // Dosya içeriğini gönder
                List<String> currentUsers = server.getDocumentManager().getFileUsers(cleanFileId);
                String usersData = String.join(",", currentUsers);

                Message response = Message.createFileContent(userId, cleanFileId, doc.getContent())
                        .addData("users", usersData)
                        .addData("filename", doc.getFileName());

                sendMessage(response);
                Protocol.log("SUCCESS: Dosya başarıyla açıldı: " + doc.getFileName() + " by " + getUserId());

            } else {
                Protocol.log("ERROR: DocumentManager.openDocument null döndü");
                sendError("Dosya bulunamadı veya açılamadı: " + actualFileId);
            }

        } catch (Exception e) {
            Protocol.logError("ERROR: Dosya açma exception: " + getUserId(), e);
            sendError("Dosya açma hatası: " + e.getMessage());
        }
    }

    /**
     * TEXT_INSERT mesajını işle
     */
    private void handleTextInsert(Message message) {
        if (!checkAuthenticated()) return;

        String fileId = message.getFileId();

        // ====== CRITICAL DEBUG ======
        Protocol.log("=== TEXT_INSERT DEBUG ===");
        Protocol.log("DEBUG: handleTextInsert - userId: " + userId);
        Protocol.log("DEBUG: handleTextInsert - fileId: '" + fileId + "'");
        Protocol.log("DEBUG: openFiles contents: " + openFiles);
        Protocol.log("DEBUG: openFiles.contains(fileId): " + openFiles.contains(fileId));
        Protocol.log("========================");

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
     * Temp client ID döndür (debug için)
     */
    private String getTempClientId() {
        return tempClientId;
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

            // UserManager'dan logout
            if (userId != null) {
                server.getUserManager().logoutUser(userId);
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
        return String.format("ClientHandler{userId='%s', username='%s', tempId='%s', connected=%s, authenticated=%s, openFiles=%d}",
                getUserId(), username, tempClientId, isConnected.get(), isAuthenticated.get(), openFiles.size());
    }
}