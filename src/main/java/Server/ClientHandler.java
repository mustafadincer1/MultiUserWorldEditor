// ClientHandler.java - WebSocket için TAMAMEN YENİDEN YAZ
package Server;

import Common.*;
import org.java_websocket.WebSocket;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSocket tabanlı İstemci bağlantısını handle eden sınıf
 * Robust OT entegreli DocumentManager ve UserManager ile çalışır
 */
public class ClientHandler {

    // Temel bağlantı bilgileri
    private final Server server;
    private final WebSocket webSocket;
    private String tempClientId;
    private String userId = null;
    private String username = null;

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
    public ClientHandler(Server server, WebSocket webSocket, String tempClientId) {
        this.server = server;
        this.webSocket = webSocket;
        this.tempClientId = tempClientId;
        this.connectionTime = System.currentTimeMillis();

        Protocol.log("WebSocket ClientHandler oluşturuldu: " + tempClientId);
    }

    /**
     * WebSocket'ten gelen mesajı işle - Ana entry point
     */
    public void onMessage(String rawMessage) {
        try {
            if (rawMessage != null && !rawMessage.trim().isEmpty()) {
                messagesReceived++;
                processMessage(rawMessage);
            }
        } catch (Exception e) {
            Protocol.logError("WebSocket mesaj işleme hatası: " + getUserId(), e);
            sendError("Mesaj işleme hatası: " + e.getMessage());
        }
    }

    /**
     * Gelen mesajı parse eder ve işler - MEVCUT KODDAN AYNEN KOPYALA
     */
    private void processMessage(String rawMessage) {
        try {
            System.out.println("RAW MESSAGE: '" + rawMessage + "'");

            // ENHANCED NEWLINE-AWARE MESSAGE PREPROCESSING - AYNEN KOPYALA
            if (rawMessage != null && rawMessage.contains("\n")) {
                long newlineCount = rawMessage.chars().filter(ch -> ch == '\n').count();
                boolean endsWithNewline = rawMessage.endsWith("\n");

                if (rawMessage.contains("__NEWLINE__")) {
                    if (!endsWithNewline) {
                        rawMessage += "\n";
                        System.out.println(" FIXED: Added missing message terminator");
                    }

                    String[] lines = rawMessage.split("\n");
                    if (lines.length > 2) {
                        System.out.println(" FIXING: Multiple newlines detected, fixing...");
                        StringBuilder fixed = new StringBuilder();
                        for (int i = 0; i < lines.length - 1; i++) {
                            if (i > 0) fixed.append("\\n");
                            fixed.append(lines[i]);
                        }
                        fixed.append("\n");
                        rawMessage = fixed.toString();
                        System.out.println(" FIXED MESSAGE: '" + rawMessage + "'");
                    }
                }
            }

            if (rawMessage == null || rawMessage.trim().isEmpty()) {
                System.err.println("ERROR: Empty message received");
                return;
            }

            // Clean message
            String cleanMessage = rawMessage.trim();
            if (cleanMessage.endsWith("\n")) {
                cleanMessage = cleanMessage.substring(0, cleanMessage.length() - 1);
            }

            String[] preliminaryParts = cleanMessage.split("\\|");
            System.out.println("DEBUG: Message parts count: " + preliminaryParts.length);

            if (preliminaryParts.length < 5) {
                System.err.println("ERROR: Invalid message format - expected 5 parts, got " +
                        preliminaryParts.length + " in: '" + cleanMessage + "'");

                if (cleanMessage.contains("__NEWLINE__")) {
                    System.err.println("SPECIAL DEBUG: __NEWLINE__ message parsing failed");

                    if (preliminaryParts.length == 4) {
                        cleanMessage = cleanMessage + "|" + System.currentTimeMillis();
                        System.err.println("DEBUG: Reconstructed message: '" + cleanMessage + "'");

                        preliminaryParts = cleanMessage.split("\\|");
                        if (preliminaryParts.length == 5) {
                            System.out.println("SUCCESS: Message reconstructed successfully");
                        }
                    }
                }

                if (preliminaryParts.length < 5) {
                    Message errorMsg = Message.createError(userId, "Geçersiz mesaj formatı: " + preliminaryParts.length + " parça");
                    sendMessage(errorMsg);
                    return;
                }
            }

            // Parse with enhanced error handling
            Message message = Message.deserialize(cleanMessage);
            if (message == null) {
                System.err.println("ERROR: Message deserialization failed for: '" + cleanMessage + "'");
                Message errorMsg = Message.createError(userId, "Mesaj parse hatası");
                sendMessage(errorMsg);
                return;
            }

            // SPECIAL DEBUG FOR __NEWLINE__ MESSAGES
            if (message.getType() == Message.MessageType.TEXT_INSERT) {
                String textData = message.getData("text");
                if ("__NEWLINE__".equals(textData)) {
                    System.out.println("🎉 SUCCESS: __NEWLINE__ message parsed successfully!");
                    System.out.println("DEBUG: Message data: " + message.getAllData());
                }
            }

            // Message type specific processing
            switch (message.getType()) {
                case TEXT_INSERT:
                    handleTextInsert(message);
                    break;
                case TEXT_DELETE:
                    handleTextDelete(message);
                    break;
                case REGISTER:
                    handleRegister(message);
                    break;
                case LOGIN:
                    handleLogin(message);
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
                case SAVE:
                    handleSave(message);
                    break;
                case FILE_DELETE:
                    handleFileDelete(message);
                    break;
                default:
                    sendError("Desteklenmeyen mesaj tipi: " + message.getType());
                    break;
            }

        } catch (Exception e) {
            System.err.println("ERROR: processMessage exception: " + e.getMessage());
            e.printStackTrace();

            try {
                Message errorMsg = Message.createError(userId, "Sunucu mesaj işleme hatası: " + e.getMessage());
                sendMessage(errorMsg);
            } catch (Exception ex) {
                System.err.println("ERROR: Could not send error message: " + ex.getMessage());
            }
        }
    }

    // === MESSAGE HANDLERS - MEVCUT KODDAN AYNEN KOPYALA ===

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
            this.userId = result.userId;
            this.username = username;
            isAuthenticated.set(true);

            if (server.registerClient(userId, this)) {
                Protocol.log("Kullanıcı giriş yaptı: " + username + " (" + userId + ")");
            } else {
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
     * FILE_LIST mesajını işle - AYNEN KOPYALA
     */
    private void handleFileList(Message message) {
        if (!checkAuthenticated()) return;

        try {
            List<DocumentManager.DocumentInfo> files =
                    server.getDocumentManager().getAllDocuments();

            Protocol.log("DEBUG: handleFileList - Bulunan dosya sayısı: " + files.size());

            StringBuilder fileListData = new StringBuilder();

            for (int i = 0; i < files.size(); i++) {
                DocumentManager.DocumentInfo file = files.get(i);
                if (i > 0) fileListData.append("|");

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
     * FILE_CREATE mesajını işle - AYNEN KOPYALA
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
                openFiles.add(fileId);

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
     * FILE_OPEN mesajını işle - AYNEN KOPYALA
     */
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
            String actualFileId = fileId;
            if (fileId.contains(":")) {
                String[] parts = fileId.split(":");
                if (parts.length >= 1) {
                    actualFileId = parts[0].trim();
                    Protocol.log("DEBUG: Client hatası düzeltildi. Gerçek fileId: '" + actualFileId + "'");
                }
            }

            String cleanFileId = actualFileId.trim();

            if (cleanFileId == null || cleanFileId.isEmpty()) {
                Protocol.log("ERROR: cleanFileId boş - input: '" + actualFileId + "'");
                sendError("Geçersiz dosya ID: " + fileId);
                return;
            }

            Protocol.log("DEBUG: İşlenecek dosya ID: '" + cleanFileId + "'");

            DocumentManager.Document doc = server.getDocumentManager().openDocument(cleanFileId, userId);

            if (doc != null) {
                openFiles.add(cleanFileId);

                List<String> currentUsers = server.getDocumentManager().getFileUsers(cleanFileId);
                String usersData = String.join(",", currentUsers);

                String docContent = doc.getContent();
                Protocol.log("DEBUG: Sending FILE_CONTENT - length: " + docContent.length() +
                        ", has newlines: " + docContent.contains("\n"));

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
     * FILE_DELETE mesajını işle - AYNEN KOPYALA
     */
    private void handleFileDelete(Message message) {
        if (!checkAuthenticated()) return;

        String fileId = message.getFileId();
        Protocol.log("DEBUG: handleFileDelete - fileId: '" + fileId + "', userId: '" + userId + "'");

        if (fileId == null || fileId.trim().isEmpty()) {
            Protocol.log("ERROR: handleFileDelete - fileId null veya boş");
            sendError("Dosya ID geçersiz");
            return;
        }

        try {
            String cleanFileId = fileId.trim();
            if (cleanFileId.contains(":")) {
                String[] parts = cleanFileId.split(":");
                if (parts.length >= 1) {
                    cleanFileId = parts[0].trim();
                    Protocol.log("DEBUG: Client hatası düzeltildi. Gerçek fileId: '" + cleanFileId + "'");
                }
            }

            Protocol.log("DEBUG: İşlenecek silme fileId: '" + cleanFileId + "'");

            boolean canDelete = server.getDocumentManager().canDeleteDocument(cleanFileId, userId);
            if (!canDelete) {
                Protocol.log("WARNING: Dosya şu anda silinemiyor (muhtemelen çoklu kullanıcı var)");

                Message response = Message.createFileDeleteAck(userId, cleanFileId, false,
                        "Dosya şu anda kullanımda, silme işlemi yapılamaz");
                sendMessage(response);
                return;
            }

            if (openFiles.contains(cleanFileId)) {
                server.getDocumentManager().closeDocument(cleanFileId, userId);
                openFiles.remove(cleanFileId);
                Protocol.log("DEBUG: Açık dosya kapatıldı: " + cleanFileId);
            }

            boolean deleted = server.getDocumentManager().deleteDocument(cleanFileId, userId);

            if (deleted) {
                Message response = Message.createFileDeleteAck(userId, cleanFileId, true,
                        "Dosya başarıyla silindi");
                sendMessage(response);

                Protocol.log("SUCCESS: Dosya silindi: " + cleanFileId + " by " + getUserId());

                broadcastFileListUpdate();

            } else {
                Protocol.log("ERROR: DocumentManager.deleteDocument false döndü");

                Message response = Message.createFileDeleteAck(userId, cleanFileId, false,
                        "Dosya silinemedi - sunucu hatası");
                sendMessage(response);
            }

        } catch (Exception e) {
            Protocol.logError("ERROR: Dosya silme exception: " + getUserId(), e);

            Message response = Message.createFileDeleteAck(userId, fileId, false,
                    "Dosya silme hatası: " + e.getMessage());
            sendMessage(response);
        }
    }

    /**
     * TEXT_INSERT mesajını işle - AYNEN KOPYALA
     */
    private void handleTextInsert(Message message) {
        if (!checkAuthenticated()) return;

        String fileId = message.getFileId();

        System.out.println("=== SERVER TEXT INSERT DEBUG ===");
        System.out.println("DEBUG: handleTextInsert - userId: " + userId);
        System.out.println("DEBUG: handleTextInsert - fileId: '" + fileId + "'");
        System.out.println("========================");

        if (!isFileOpen(fileId)) {
            sendError("Dosya açık değil");
            return;
        }

        try {
            Integer position = message.getDataAsInt("position");
            String textValue = message.getData("text");

            String text;
            if ("__SPACE__".equals(textValue)) {
                text = " ";
                System.out.println("DEBUG: *** SERVER - SPACE CHARACTER DECODED ***");
            } else if ("__NEWLINE__".equals(textValue)) {
                text = "\n";
                System.out.println("DEBUG: *** SERVER - NEWLINE CHARACTER DECODED ***");
            } else if ("__CRLF__".equals(textValue)) {
                text = "\r\n";
                System.out.println("DEBUG: *** SERVER - CRLF CHARACTER DECODED ***");
            } else if ("__TAB__".equals(textValue)) {
                text = "\t";
                System.out.println("DEBUG: *** SERVER - TAB CHARACTER DECODED ***");
            } else {
                text = textValue;
            }

            System.out.println("DEBUG: Final decoded text: '" + text + "' (length: " +
                    (text != null ? text.length() : "null") + ")");

            if (position == null || text == null) {
                System.out.println("ERROR: Invalid data - position: " + position + ", text: " +
                        (text != null ? "'" + text + "'" : "null"));
                sendError("Position ve text gerekli");
                return;
            }

            DocumentManager.InsertResult result = server.getDocumentManager()
                    .insertTextWithResult(fileId, position, text, userId);

            if (result.success) {
                String broadcastText;
                if (text.equals(" ")) {
                    broadcastText = "__SPACE__";
                } else if (text.equals("\n")) {
                    broadcastText = "__NEWLINE__";
                    System.out.println("DEBUG: SERVER - Newline encoded for broadcast");
                } else if (text.equals("\r\n")) {
                    broadcastText = "__CRLF__";
                } else if (text.equals("\t")) {
                    broadcastText = "__TAB__";
                } else {
                    broadcastText = text;
                }

                Message updateMsg = Message.createTextUpdate(userId, fileId, "insert",
                        result.appliedPosition, broadcastText);
                server.broadcastToFile(fileId, updateMsg, userId);

                if (text.equals("\n")) {
                    System.out.println("🎉 SERVER SUCCESS: NEWLINE CHARACTER INSERTED! 🎉");
                } else if (text.equals(" ")) {
                    System.out.println("🎉 SERVER SUCCESS: SPACE CHARACTER INSERTED! 🎉");
                }

            } else {
                System.out.println("ERROR: DocumentManager.insertTextWithResult failed");
                sendError("Metin eklenemedi");
            }

        } catch (Exception e) {
            System.err.println("Text insert hatası: " + getUserId() + " - " + e.getMessage());
            e.printStackTrace();
            sendError("Metin ekleme hatası");
        }
    }

    /**
     * TEXT_DELETE mesajını işle - AYNEN KOPYALA
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

            boolean success = server.getDocumentManager()
                    .deleteText(fileId, position, length, userId);

            if (success) {
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
     * SAVE mesajını işle - AYNEN KOPYALA
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

    // === WEBSOCKET SPECIFIC METHODS ===

    /**
     * İstemciye mesaj gönder - WebSocket version
     */
    public void sendMessage(Message message) {
        if (!isConnected.get() || !webSocket.isOpen()) {
            return;
        }

        try {
            String serialized = message.serialize();

            if (message.getType() == Message.MessageType.FILE_CONTENT) {
                String content = message.getData("content");
                if (content != null && (content.contains("\n") || content.contains("\r"))) {
                    Protocol.log("DEBUG: WebSocket FILE_CONTENT has newlines - length: " + content.length());
                }
            }

            webSocket.send(serialized);
            messagesSent++;

            Protocol.log("DEBUG: WebSocket message sent: " + message.getType() + " to " + getUserId());

        } catch (Exception e) {
            Protocol.logError("WebSocket mesaj gönderme hatası: " + getUserId(), e);
            disconnect();
        }
    }

    /**
     * WebSocket bağlantı hatası
     */
    public void onError(Exception ex) {
        Protocol.logError("WebSocket hatası: " + getUserId(), ex);
        disconnect();
    }

    /**
     * WebSocket bağlantı kapandı
     */
    public void onClose(int code, String reason, boolean remote) {
        Protocol.log("WebSocket kapandı: " + getUserId() + " - Code: " + code + ", Reason: " + reason);
        disconnect();
    }

    /**
     * Bağlantıyı kapat - WebSocket version
     */
    public void disconnect() {
        if (!isConnected.compareAndSet(true, false)) {
            return;
        }

        Protocol.log("WebSocket bağlantısı kapatılıyor: " + getUserId());

        try {
            synchronized (openFiles) {
                for (String fileId : openFiles) {
                    server.getDocumentManager().closeDocument(fileId, userId);
                }
                openFiles.clear();
            }

            if (userId != null) {
                server.getUserManager().logoutUser(userId);
                server.unregisterClient(userId);
            }

            if (webSocket.isOpen()) {
                webSocket.close(1000, "Normal closure");
            }

        } catch (Exception e) {
            Protocol.logError("WebSocket disconnect hatası: " + getUserId(), e);
        }
    }

    // === UTILITY METHODS - AYNEN KOPYALA ===

    private void sendError(String errorMessage) {
        Message errorMsg = Message.createError(userId, errorMessage);
        sendMessage(errorMsg);
    }

    private boolean checkAuthenticated() {
        if (!isAuthenticated.get()) {
            sendError("Önce giriş yapmalısınız");
            return false;
        }
        return true;
    }

    private boolean isFileOpen(String fileId) {
        return fileId != null && openFiles.contains(fileId);
    }

    private String getTempClientId() {
        return tempClientId;
    }

    private void broadcastFileListUpdate() {
        try {
            Message updateMsg = new Message(Message.MessageType.FILE_LIST_RESP, null, null)
                    .addData("update", "refresh");

            server.broadcastToAll(updateMsg, userId);

            Protocol.log("DEBUG: File list update broadcast gönderildi");

        } catch (Exception e) {
            Protocol.log("DEBUG: File list update broadcast hatası: " + e.getMessage());
        }
    }

    // === GETTER METHODS ===

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
        synchronized (openFiles) {
            return new HashSet<>(openFiles);
        }
    }

    public long getConnectionDuration() {
        return System.currentTimeMillis() - connectionTime;
    }

    @Override
    public String toString() {
        return String.format("WebSocketClientHandler{userId='%s', username='%s', tempId='%s', connected=%s, authenticated=%s, openFiles=%d}",
                getUserId(), username, tempClientId, isConnected.get(), isAuthenticated.get(), openFiles.size());
    }
}