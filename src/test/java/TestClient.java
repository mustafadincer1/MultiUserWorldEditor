import Common.Message;
import Exception.MessageParseException;

import java.io.*;
import java.net.*;
import java.util.Scanner;


/**
 * Server test etmek için basit console client
 */
import Common.*;
import java.io.*;
import java.net.*;
import java.util.Scanner;

/**
 * Server test etmek için basit console client
 */
import Common.*;
import java.io.*;
import java.net.*;
import java.util.Scanner;

/**
 * Server test etmek için basit console client
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

        System.out.println("=== MTP Test Client ===");
        System.out.print("Server IP (default: localhost): ");
        String host = scanner.nextLine().trim();
        if (host.isEmpty()) host = "localhost";

        System.out.print("Port (default: 8080): ");
        String portStr = scanner.nextLine().trim();
        int port = portStr.isEmpty() ? 8080 : Integer.parseInt(portStr);

        // Server'a bağlan
        if (connect(host, port)) {
            System.out.println("Server'a bağlanıldı: " + host + ":" + port);

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
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream()), true);
            connected = true;
            return true;

        } catch (IOException e) {
            System.err.println("Bağlantı hatası: " + e.getMessage());
            return false;
        }
    }

    private void startResponseListener() {
        Thread responseThread = new Thread(() -> {
            try {
                String response;
                while (connected && (response = reader.readLine()) != null) {
                    System.out.println("RAW RESPONSE: " + response); // DEBUG
                    handleResponse(response);
                }
            } catch (IOException e) {
                if (connected) {
                    System.err.println("Response okuma hatası: " + e.getMessage());
                }
            }
        });

        responseThread.setDaemon(true);
        responseThread.start();
    }

    private void handleResponse(String rawResponse) {
        try {
            Message response = Message.deserialize(rawResponse + "\n");

            System.out.println("\n--- SERVER RESPONSE ---");
            System.out.println("Type: " + response.getType());
            System.out.println("User ID: " + response.getUserId());
            System.out.println("File ID: " + response.getFileId());
            System.out.println("Data: " + response.getData());
            System.out.println("----------------------");

            // Özel response handling
            switch (response.getType()) {
                case CONNECT_ACK:
                    String status = response.getDataValue("status");
                    if ("success".equals(status)) {
                        userId = response.getDataValue("userId");
                        System.out.println("✅ Başarıyla giriş yapıldı! User ID: " + userId);
                    } else {
                        System.out.println("❌ Giriş başarısız!");
                    }
                    break;

                case FILE_LIST_RESP:
                    System.out.println("📁 Dosya listesi alındı");
                    break;

                case FILE_CONTENT:
                    String content = response.getDataValue("content");
                    System.out.println("📄 Dosya içeriği: \"" + content + "\"");
                    break;

                case TEXT_UPDATE:
                    String operation = response.getDataValue("operation");
                    String text = response.getDataValue("text");
                    String position = response.getDataValue("position");
                    System.out.println("✏️  Text " + operation + " at position " + position + ": \"" + text + "\"");
                    break;

                case ERROR:
                    String errorCode = response.getDataValue("code");
                    String errorMsg = response.getDataValue("message");
                    System.out.println("❌ ERROR " + errorCode + ": " + errorMsg);
                    break;
            }

        } catch (MessageParseException e) {
            System.err.println("Response parse hatası: " + e.getMessage());
            System.err.println("Raw response: " + rawResponse);
        }
    }

    private void commandLoop(Scanner scanner) {
        System.out.println("\n=== KOMUTLAR ===");
        showHelp();

        while (connected) {
            System.out.print("\nKomut> ");
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
                        testConnection();
                        break;

                    case "raw":
                        if (parts.length < 2) {
                            System.out.println("Usage: raw <message>");
                            break;
                        }
                        sendRawMessage(parts[1]);
                        break;

                    case "quit":
                    case "q":
                        connected = false;
                        break;

                    default:
                        System.out.println("Bilinmeyen komut: " + command + ". 'help' yazın.");
                        break;
                }

            } catch (Exception e) {
                System.err.println("Komut hatası: " + e.getMessage());
            }
        }
    }

    private void showHelp() {
        System.out.println("Kullanılabilir komutlar:");
        System.out.println("  connect <username>     - Server'a giriş yap");
        System.out.println("  list                   - Dosya listesini al");
        System.out.println("  create <filename>      - Yeni dosya oluştur");
        System.out.println("  open <fileId>          - Dosya aç");
        System.out.println("  insert <fileId> <pos> <text> - Metne ekleme yap (quotes olmadan)");
        System.out.println("  delete <fileId> <pos> <len>  - Metinden silme yap");
        System.out.println("  save <fileId>          - Dosyayı kaydet");
        System.out.println("  raw <message>          - Ham mesaj gönder");
        System.out.println("  help                   - Bu yardımı göster");
        System.out.println("  quit                   - Çıkış");
    }

    private void sendConnect(String username) {
        Message msg = Message.createConnectMessage(username);
        sendMessage(msg);
        System.out.println("Connect mesajı gönderildi: " + username);
    }

    private void sendFileList() {
        if (userId == null) {
            System.out.println("Önce giriş yapmalısınız!");
            return;
        }

        Message msg = new Message(Message.MessageType.FILE_LIST, userId, null, "{}");
        sendMessage(msg);
        System.out.println("Dosya listesi istendi");
    }

    private void sendFileCreate(String filename) {
        if (userId == null) {
            System.out.println("Önce giriş yapmalısınız!");
            return;
        }

        String data = String.format("{\"name\":\"%s\"}", filename);
        Message msg = new Message(Message.MessageType.FILE_CREATE, userId, null, data);
        sendMessage(msg);
        System.out.println("Dosya oluşturma istendi: " + filename);
    }

    private void sendFileOpen(String fileId) {
        if (userId == null) {
            System.out.println("Önce giriş yapmalısınız!");
            return;
        }

        String data = String.format("{\"fileId\":\"%s\"}", fileId);
        Message msg = new Message(Message.MessageType.FILE_OPEN, userId, null, data);
        sendMessage(msg);
        System.out.println("Dosya açma istendi: " + fileId);
    }

    private void handleInsertCommand(String params) {
        String[] parts = params.trim().split("\\s+", 3); // whitespace'leri handle et
        if (parts.length < 3) {
            System.out.println("Usage: insert <fileId> <position> <text>");
            System.out.println("Örnek: insert file_123 0 Hello World (quotes KULLANMAYIN)");
            return;
        }

        try {
            String fileId = parts[0];
            int position = Integer.parseInt(parts[1]);
            String text = parts[2];

            // Quotes'ları temizle (eğer varsa)
            text = text.replace("\"", ""); // Tüm quotes'ları kaldır

            System.out.println("DEBUG: Gönderilecek text: '" + text + "'"); // DEBUG

            Message msg = Message.createTextInsertMessage(userId, fileId, position, text);
            sendMessage(msg);
            System.out.println("Text insert: pos=" + position + " text=\"" + text + "\"");

        } catch (NumberFormatException e) {
            System.out.println("Hata: Position sayı olmalı! Girilen: '" + parts[1] + "'");
            System.out.println("Usage: insert <fileId> <position> <text>");
        }
    }

    private void handleDeleteCommand(String params) {
        String[] parts = params.split(" ");
        if (parts.length < 3) {
            System.out.println("Usage: delete <fileId> <position> <length>");
            return;
        }

        String fileId = parts[0];
        int position = Integer.parseInt(parts[1]);
        int length = Integer.parseInt(parts[2]);

        Message msg = Message.createTextDeleteMessage(userId, fileId, position, length);
        sendMessage(msg);
        System.out.println("Text delete: pos=" + position + " len=" + length);
    }

    private void sendSave(String fileId) {
        if (userId == null) {
            System.out.println("Önce giriş yapmalısınız!");
            return;
        }

        Message msg = new Message(Message.MessageType.SAVE, userId, fileId, "{}");
        sendMessage(msg);
        System.out.println("Save istendi: " + fileId);
    }

    private void testConnection() {
        try {
            // Basit ping test
            writer.println("PING");
            System.out.println("PING gönderildi");

            // Manual CONNECT test
            String connectMsg = "CONNECT|22|null|null|{\"username\":\"test\"}|" + System.currentTimeMillis();
            writer.println(connectMsg);
            System.out.println("Manual CONNECT gönderildi: " + connectMsg);

        } catch (Exception e) {
            System.err.println("Test hatası: " + e.getMessage());
        }
    }

    private void sendRawMessage(String rawMessage) {
        if (!rawMessage.endsWith("\n")) {
            rawMessage += "\n"; // \n yoksa ekle
        }
        writer.print(rawMessage);
        writer.flush();
        System.out.println("Ham mesaj gönderildi: " + rawMessage.trim());
    }

    private void sendMessage(Message message) {
        if (!connected) {
            System.out.println("Bağlantı yok!");
            return;
        }

        String serialized = message.serialize();
        writer.print(serialized); // serialize() zaten \n içeriyor
        writer.flush(); // Flush ekledik
        System.out.println(">>> " + serialized.trim());
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

        System.out.println("Bağlantı kapatıldı.");
    }
}