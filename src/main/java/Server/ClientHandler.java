package Server;

import Common.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static Common.Utils.writeToSocket;

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
            System.out.println("RAW MESSAGE: '" + rawMessage + "'");

            //  ENHANCED NEWLINE-AWARE MESSAGE PREPROCESSING
            if (rawMessage != null && rawMessage.contains("\n")) {
                long newlineCount = rawMessage.chars().filter(ch -> ch == '\n').count();
                boolean endsWithNewline = rawMessage.endsWith("\n");



                if (rawMessage.contains("__NEWLINE__")) {


                    // Ensure proper message termination for __NEWLINE__ messages
                    if (!endsWithNewline) {
                        rawMessage += "\n";
                        System.out.println(" FIXED: Added missing message terminator");
                    }

                    // Remove any embedded newlines that aren't the message terminator
                    String[] lines = rawMessage.split("\n");
                    if (lines.length > 2) { // More than expected (message + empty terminator)
                        System.out.println(" FIXING: Multiple newlines detected, fixing...");
                        StringBuilder fixed = new StringBuilder();
                        for (int i = 0; i < lines.length - 1; i++) {
                            if (i > 0) fixed.append("\\n"); // Escape internal newlines
                            fixed.append(lines[i]);
                        }
                        fixed.append("\n"); // Add proper terminator
                        rawMessage = fixed.toString();
                        System.out.println(" FIXED MESSAGE: '" + rawMessage + "'");
                    }
                }
            }


            if (rawMessage == null || rawMessage.trim().isEmpty()) {
                System.err.println("ERROR: Empty message received");
                return;
            }

            // Clean message (remove trailing newlines for parsing)
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
                    System.err.println("DEBUG: Raw message bytes: " + java.util.Arrays.toString(rawMessage.getBytes()));

                    // Try to reconstruct the message
                    if (preliminaryParts.length == 4) {
                        // Missing timestamp - add current timestamp
                        cleanMessage = cleanMessage + "|" + System.currentTimeMillis();
                        System.err.println("DEBUG: Reconstructed message: '" + cleanMessage + "'");

                        // Retry parsing
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

            //  SPECIAL DEBUG FOR __NEWLINE__ MESSAGES
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

                String docContent = doc.getContent();
                Protocol.log("DEBUG: Sending FILE_CONTENT - length: " + docContent.length() +
                        ", has newlines: " + docContent.contains("\n"));
                if (docContent.contains("\n")) {
                    Protocol.log("DEBUG: Content preview: '" + docContent.replace("\n", "\\n") + "'");
                }

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
            // FileId'yi temizle (client hatalarına karşı)
            String cleanFileId = fileId.trim();
            if (cleanFileId.contains(":")) {
                String[] parts = cleanFileId.split(":");
                if (parts.length >= 1) {
                    cleanFileId = parts[0].trim();
                    Protocol.log("DEBUG: Client hatası düzeltildi. Gerçek fileId: '" + cleanFileId + "'");
                }
            }

            Protocol.log("DEBUG: İşlenecek silme fileId: '" + cleanFileId + "'");

            // Dosyanın silinebilir olup olmadığını kontrol et
            boolean canDelete = server.getDocumentManager().canDeleteDocument(cleanFileId, userId);
            if (!canDelete) {
                Protocol.log("WARNING: Dosya şu anda silinemiyor (muhtemelen çoklu kullanıcı var)");

                Message response = Message.createFileDeleteAck(userId, cleanFileId, false,
                        "Dosya şu anda kullanımda, silme işlemi yapılamaz");
                sendMessage(response);
                return;
            }

            // Eğer dosya açıksa önce kapat
            if (openFiles.contains(cleanFileId)) {
                server.getDocumentManager().closeDocument(cleanFileId, userId);
                openFiles.remove(cleanFileId);
                Protocol.log("DEBUG: Açık dosya kapatıldı: " + cleanFileId);
            }

            // Dosyayı sil
            boolean deleted = server.getDocumentManager().deleteDocument(cleanFileId, userId);

            if (deleted) {
                // Başarılı silme response'u
                Message response = Message.createFileDeleteAck(userId, cleanFileId, true,
                        "Dosya başarıyla silindi");
                sendMessage(response);

                Protocol.log("SUCCESS: Dosya silindi: " + cleanFileId + " by " + getUserId());

                // Diğer kullanıcılara dosya listesi güncellemesi broadcast et (opsiyonel)
                // Böylece diğer kullanıcıların dosya listesi otomatik güncellenir
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
     *  NEW: Diğer kullanıcılara dosya listesi güncellemesi gönder
     */
    private void broadcastFileListUpdate() {
        try {
            // Tüm bağlı kullanıcılara FILE_LIST_UPDATE mesajı gönder
            Message updateMsg = new Message(Message.MessageType.FILE_LIST_RESP, null, null)
                    .addData("update", "refresh");

            server.broadcastToAll(updateMsg, userId);

            Protocol.log("DEBUG: File list update broadcast gönderildi");

        } catch (Exception e) {
            Protocol.log("DEBUG: File list update broadcast hatası: " + e.getMessage());
        }
    }

        /**
         * TEXT_INSERT mesajını işle - SPACE CHARACTER FIX
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

            //  SPECIAL CHARACTERS MARKER DECODING
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
            System.out.println("DEBUG: Original position from client: " + position);

            // Validation
            if (position == null || text == null) {
                System.out.println("ERROR: Invalid data - position: " + position + ", text: " +
                        (text != null ? "'" + text + "'" : "null"));
                sendError("Position ve text gerekli");
                return;
            }

            //  USE NEW insertTextWithResult METHOD
            DocumentManager.InsertResult result = server.getDocumentManager()
                    .insertTextWithResult(fileId, position, text, userId);

            if (result.success) {
                //  BROADCAST WITH ACTUAL APPLIED POSITION (NOT ORIGINAL)
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

                // ✅ USE RESULT.APPLIEDPOSITION INSTEAD OF ORIGINAL POSITION
                Message updateMsg = Message.createTextUpdate(userId, fileId, "insert",
                        result.appliedPosition, broadcastText);
                server.broadcastToFile(fileId, updateMsg, userId);

                // Success log with both positions
                if (text.equals("\n")) {
                    System.out.println("🎉 SERVER SUCCESS: NEWLINE CHARACTER INSERTED! 🎉");
                    System.out.println("Text insert: " + fileId + " CLIENT pos:" + position +
                            " → SERVER pos:" + result.appliedPosition + " NEWLINE by " + getUserId());
                } else if (text.equals(" ")) {
                    System.out.println("🎉 SERVER SUCCESS: SPACE CHARACTER INSERTED! 🎉");
                    System.out.println("Text insert: " + fileId + " CLIENT pos:" + position +
                            " → SERVER pos:" + result.appliedPosition + " SPACE by " + getUserId());
                } else {
                    System.out.println("Text insert: " + fileId + " CLIENT pos:" + position +
                            " → SERVER pos:" + result.appliedPosition + " text:'" + text + "' by " + getUserId());
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
     * İstemciye mesaj gönder - newline-safe version
     */
    /**
     * İstemciye mesaj gönder - debug enhanced version
     */
    public void sendMessage(Message message) {
        if (!isConnected.get() || clientSocket.isClosed()) {
            return;
        }

        try {
            String serialized = message.serialize();

            //  FILE_CONTENT mesajları için özel debug
            if (message.getType() == Message.MessageType.FILE_CONTENT) {
                String content = message.getData("content");
                if (content != null && (content.contains("\n") || content.contains("\r"))) {
                    Protocol.log("DEBUG: FILE_CONTENT has newlines - length: " + content.length());
                    Protocol.log("DEBUG: Content preview: '" + content.replace("\n", "\\n").replace("\r", "\\r") + "'");
                    Protocol.log("DEBUG: Full serialized message length: " + serialized.length());
                }
            }

            writeToSocket(clientSocket, serialized);
            messagesSent++;

            Protocol.log("DEBUG: Message sent successfully: " + message.getType() + " to " + getUserId());

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