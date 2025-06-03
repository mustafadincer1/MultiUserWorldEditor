import Common.*;
import java.io.*;
import java.net.*;
import java.util.Scanner;

/**
 * MTP Server test etmek için basit console client
 * Yeni Message formatı ve sadeleştirilmiş protokol ile uyumlu
 */
public class TestClient {

    private Socket socket;
    private BufferedReader reader;
    private PrintWriter writer;
    private String userId = null;
    private boolean connected = false;

    public static void main(String[] args) {
        TestClient client = new TestClient();
        client.start();
    }

    public void start() {
        Scanner scanner = new Scanner(System.in);

        System.out.println("=== " + Protocol.PROJECT_NAME + " Test Client ===");
        System.out.print("Server IP (default: localhost): ");
        String host = scanner.nextLine().trim();
        if (host.isEmpty()) host = "localhost";

        System.out.print("Port (default: " + Protocol.DEFAULT_PORT + "): ");
        String portStr = scanner.nextLine().trim();
        int port = portStr.isEmpty() ? Protocol.DEFAULT_PORT : Integer.parseInt(portStr);

        // Server'a bağlan
        if (connect(host, port)) {
            System.out.println("✅ Server'a bağlanıldı: " + host + ":" + port);

            // Response listener thread başlat
            startResponseListener();

            // Ana command loop
            commandLoop(scanner);
        }

        disconnect();
        scanner.close();
    }

    private boolean connect(String host, int port) {
        try {
            socket = new Socket(host, port);
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);
            connected = true;
            return true;

        } catch (IOException e) {
            System.err.println("❌ Bağlantı hatası: " + e.getMessage());
            return false;
        }
    }

    private void startResponseListener() {
        Thread responseThread = new Thread(() -> {
            try {
                String response;
                while (connected && (response = reader.readLine()) != null) {
                    System.out.println("\n[RAW] " + response); // DEBUG
                    handleResponse(response);
                }
            } catch (IOException e) {
                if (connected) {
                    System.err.println("❌ Response okuma hatası: " + e.getMessage());
                }
            }
        });

        responseThread.setDaemon(true);
        responseThread.start();
    }

    private void handleResponse(String rawResponse) {
        try {
            // Yeni Message formatı ile deserialize
            Message response = Message.deserialize(rawResponse + "\n");

            System.out.println("--- SERVER RESPONSE ---");
            System.out.println("Type: " + response.getType());
            System.out.println("User ID: " + response.getUserId());
            System.out.println("File ID: " + response.getFileId());
            System.out.println("Timestamp: " + response.getTimestamp());

            // Data içeriğini göster
            if (response.getData("content") != null) {
                System.out.println("Content: \"" + response.getData("content") + "\"");
            }
            if (response.getData("status") != null) {
                System.out.println("Status: " + response.getData("status"));
            }
            if (response.getData("message") != null) {
                System.out.println("Message: " + response.getData("message"));
            }

            System.out.println("----------------------\n");

            // Özel response handling
            switch (response.getType()) {
                case CONNECT_ACK:
                    String status = response.getData("status");
                    if ("success".equals(status)) {
                        userId = response.getUserId();
                        System.out.println("✅ Başarıyla giriş yapıldı! User ID: " + userId);
                    } else {
                        System.out.println("❌ Giriş başarısız!");
                    }
                    break;

                case FILE_LIST_RESP:
                    String files = response.getData("files");
                    System.out.println("📁 Dosya listesi:");
                    if (files != null && !files.isEmpty()) {
                        String[] fileEntries = files.split(",");
                        for (String entry : fileEntries) {
                            String[] parts = entry.split(":");
                            if (parts.length >= 3) {
                                System.out.println("  " + parts[0] + " - " + parts[1] + " (users: " + parts[2] + ")");
                            }
                        }
                    } else {
                        System.out.println("  (Dosya yok)");
                    }
                    break;

                case FILE_CONTENT:
                    String content = response.getData("content");
                    String name = response.getData("name");
                    String users = response.getData("users");
                    System.out.println("📄 Dosya açıldı" + (name != null ? " (" + name + ")" : ""));
                    System.out.println("   İçerik: \"" + (content != null ? content : "") + "\"");
                    if (users != null && !users.isEmpty()) {
                        System.out.println("   Aktif kullanıcılar: " + users);
                    }
                    break;

                case TEXT_UPDATE:
                    String operation = response.getData("operation");
                    String text = response.getData("text");
                    String position = response.getData("position");
                    String length = response.getData("length");

                    if ("insert".equals(operation)) {
                        System.out.println("✏️  " + response.getUserId() + " ekledi: \"" + text + "\" (pos: " + position + ")");
                    } else if ("delete".equals(operation)) {
                        System.out.println("🗑️  " + response.getUserId() + " sildi: " + length + " karakter (pos: " + position + ")");
                    }
                    break;

                case SAVE:
                    String saveStatus = response.getData("status");
                    if ("success".equals(saveStatus)) {
                        System.out.println("💾 Dosya başarıyla kaydedildi!");
                    } else {
                        System.out.println("❌ Dosya kaydedilemedi!");
                    }
                    break;

                case ERROR:
                    String errorMsg = response.getData("message");
                    System.out.println("❌ ERROR: " + errorMsg);
                    break;

                default:
                    System.out.println("ℹ️  Bilinmeyen response: " + response.getType());
                    break;
            }

        } catch (Exception e) {
            System.err.println("❌ Response parse hatası: " + e.getMessage());
            System.err.println("   Raw response: " + rawResponse);
        }
    }

    private void commandLoop(Scanner scanner) {
        System.out.println("\n=== KOMUTLAR ===");
        showHelp();

        while (connected) {
            System.out.print("\n[" + (userId != null ? userId : "guest") + "]> ");
            String input = scanner.nextLine().trim();

            if (input.isEmpty()) continue;

            String[] parts = input.split(" ", 2);
            String command = parts[0].toLowerCase();

            try {
                switch (command) {
                    case "help":
                    case "h":
                        showHelp();
                        break;

                    case "connect":
                    case "c":
                        if (parts.length < 2) {
                            System.out.println("Usage: connect <username>");
                            break;
                        }
                        sendConnect(parts[1]);
                        break;

                    case "list":
                    case "l":
                        sendFileList();
                        break;

                    case "create":
                        if (parts.length < 2) {
                            System.out.println("Usage: create <filename>");
                            break;
                        }
                        sendFileCreate(parts[1]);
                        break;

                    case "open":
                    case "o":
                        if (parts.length < 2) {
                            System.out.println("Usage: open <fileId>");
                            break;
                        }
                        sendFileOpen(parts[1]);
                        break;

                    case "insert":
                    case "i":
                        if (parts.length < 2) {
                            System.out.println("Usage: insert <fileId> <position> <text>");
                            System.out.println("Örnek: insert file_123 0 Hello World");
                            break;
                        }
                        handleInsertCommand(parts[1]);
                        break;

                    case "delete":
                    case "d":
                        if (parts.length < 2) {
                            System.out.println("Usage: delete <fileId> <position> <length>");
                            break;
                        }
                        handleDeleteCommand(parts[1]);
                        break;

                    case "save":
                    case "s":
                        if (parts.length < 2) {
                            System.out.println("Usage: save <fileId>");
                            break;
                        }
                        sendSave(parts[1]);
                        break;

                    case "test":
                        testMultiUser();
                        break;

                    case "raw":
                        if (parts.length < 2) {
                            System.out.println("Usage: raw <message>");
                            break;
                        }
                        sendRawMessage(parts[1]);
                        break;

                    case "status":
                        showStatus();
                        break;

                    case "quit":
                    case "q":
                        sendDisconnect();
                        connected = false;
                        break;

                    default:
                        System.out.println("❓ Bilinmeyen komut: " + command + ". 'help' yazın.");
                        break;
                }

            } catch (Exception e) {
                System.err.println("❌ Komut hatası: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private void showHelp() {
        System.out.println("📋 Kullanılabilir komutlar:");
        System.out.println("  connect <username>     - Server'a giriş yap");
        System.out.println("  list                   - Dosya listesini al");
        System.out.println("  create <filename>      - Yeni dosya oluştur");
        System.out.println("  open <fileId>          - Dosya aç");
        System.out.println("  insert <fileId> <pos> <text> - Metne ekleme yap");
        System.out.println("  delete <fileId> <pos> <len>  - Metinden silme yap");
        System.out.println("  save <fileId>          - Dosyayı kaydet");
        System.out.println("  test                   - Multi-user test");
        System.out.println("  status                 - Bağlantı durumu");
        System.out.println("  raw <message>          - Ham mesaj gönder");
        System.out.println("  help                   - Bu yardımı göster");
        System.out.println("  quit                   - Çıkış");
    }

    private void sendConnect(String username) {
        if (!Protocol.isValidUsername(username)) {
            System.out.println("❌ Geçersiz kullanıcı adı! (3-30 karakter, harf/rakam/_ -)");
            return;
        }

        Message msg = Message.createConnect(username);
        sendMessage(msg);
        System.out.println("📤 Connect mesajı gönderildi: " + username);
    }

    private void sendFileList() {
        if (!checkAuthenticated()) return;

        Message msg = Message.createFileList();
        sendMessage(msg);
        System.out.println("📤 Dosya listesi istendi");
    }

    private void sendFileCreate(String filename) {
        if (!checkAuthenticated()) return;

        if (!Protocol.isValidFilename(filename)) {
            System.out.println("❌ Geçersiz dosya ismi!");
            return;
        }

        Message msg = Message.createFileCreate(userId, filename);
        sendMessage(msg);
        System.out.println("📤 Dosya oluşturma istendi: " + filename);
    }

    private void sendFileOpen(String fileId) {
        if (!checkAuthenticated()) return;

        Message msg = Message.createFileOpen(userId, fileId);
        sendMessage(msg);
        System.out.println("📤 Dosya açma istendi: " + fileId);
    }

    private void handleInsertCommand(String params) {
        if (!checkAuthenticated()) return;

        String[] parts = params.trim().split("\\s+", 3);
        if (parts.length < 3) {
            System.out.println("Usage: insert <fileId> <position> <text>");
            return;
        }

        try {
            String fileId = parts[0];
            int position = Integer.parseInt(parts[1]);
            String text = parts[2];

            Message msg = Message.createTextInsert(userId, fileId, position, text);
            sendMessage(msg);
            System.out.println("📤 Text insert: pos=" + position + " text=\"" + text + "\"");

        } catch (NumberFormatException e) {
            System.out.println("❌ Position sayı olmalı!");
        }
    }

    private void handleDeleteCommand(String params) {
        if (!checkAuthenticated()) return;

        String[] parts = params.split(" ");
        if (parts.length < 3) {
            System.out.println("Usage: delete <fileId> <position> <length>");
            return;
        }

        try {
            String fileId = parts[0];
            int position = Integer.parseInt(parts[1]);
            int length = Integer.parseInt(parts[2]);

            Message msg = Message.createTextDelete(userId, fileId, position, length);
            sendMessage(msg);
            System.out.println("📤 Text delete: pos=" + position + " len=" + length);

        } catch (NumberFormatException e) {
            System.out.println("❌ Position ve length sayı olmalı!");
        }
    }

    private void sendSave(String fileId) {
        if (!checkAuthenticated()) return;

        Message msg = Message.createSave(userId, fileId);
        sendMessage(msg);
        System.out.println("📤 Save istendi: " + fileId);
    }

    private void sendDisconnect() {
        if (userId != null) {
            Message msg = Message.createDisconnect(userId);
            sendMessage(msg);
            System.out.println("📤 Disconnect gönderildi");
        }
    }

    private void testMultiUser() {
        System.out.println("🧪 Multi-user test başlatılıyor...");
        System.out.println("   1. Başka terminal açın: java TestClient");
        System.out.println("   2. Aynı dosyayı açın");
        System.out.println("   3. Aynı anda yazı yazın");
        System.out.println("   4. Operational Transform'u gözlemleyin!");
    }

    private void showStatus() {
        System.out.println("🔗 Bağlantı Durumu:");
        System.out.println("   Connected: " + connected);
        System.out.println("   User ID: " + (userId != null ? userId : "null"));
        System.out.println("   Socket: " + (socket != null && !socket.isClosed() ? "Open" : "Closed"));
    }

    private void sendRawMessage(String rawMessage) {
        if (!rawMessage.endsWith("\n")) {
            rawMessage += "\n";
        }
        writer.print(rawMessage);
        writer.flush();
        System.out.println("📤 Ham mesaj: " + rawMessage.trim());
    }

    private void sendMessage(Message message) {
        if (!connected) {
            System.out.println("❌ Bağlantı yok!");
            return;
        }

        String serialized = message.serialize();
        writer.print(serialized);
        writer.flush();
        System.out.println("📤 " + serialized.trim());
    }

    private boolean checkAuthenticated() {
        if (userId == null) {
            System.out.println("❌ Önce giriş yapmalısınız! (connect <username>)");
            return false;
        }
        return true;
    }

    private void disconnect() {
        connected = false;

        try {
            if (socket != null && !socket.isClosed()) {
                socket.close();
            }
        } catch (IOException e) {
            System.err.println("Disconnect hatası: " + e.getMessage());
        }

        System.out.println("👋 Bağlantı kapatıldı.");
    }
}