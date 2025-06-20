// Server.java - TAMAMEN YENİDEN YAZ (WebSocket Only)
package Server;

import Common.*;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MTP (Multi-user Text Protocol) WebSocket Server
 * Robust OT entegreli DocumentManager ve UserManager ile çalışır
 */
public class Server {

    // Server konfigürasyonu
    private final int port;
    private WebSocketServer wsServer;
    private boolean isRunning = false;
    private final Object serverLock = new Object();

    // İstemci yönetimi
    private final Map<String, ClientHandler> connectedClients = new ConcurrentHashMap<>();
    private final Map<WebSocket, ClientHandler> webSocketHandlers = new ConcurrentHashMap<>();
    private final AtomicInteger clientCounter = new AtomicInteger(0);

    // Manager'lar
    private DocumentManager documentManager;
    private UserManager userManager;

    // Server başlangıç zamanı
    private final long startTime;

    /**
     * Constructor - varsayılan port
     */
    public Server() {
        this(Protocol.DEFAULT_PORT); // Protocol.java'dan DEFAULT_PORT kullan (8080)
    }

    /**
     * Constructor - özel port
     */
    public Server(int port) {
        if (!Protocol.isValidPort(port)) {
            throw new IllegalArgumentException("Geçersiz port: " + port);
        }

        this.port = port;
        this.startTime = System.currentTimeMillis();

        // Manager'ları başlat
        this.userManager = new UserManager();
        this.documentManager = new DocumentManager();

        Protocol.log("WebSocket Server oluşturuldu. Port: " + port);
    }

    /**
     * WebSocket sunucusunu başlat
     */
    public void start() throws Exception {
        synchronized (serverLock) {
            if (isRunning) {
                Protocol.log("WebSocket Server zaten çalışıyor!");
                return;
            }

            try {
                wsServer = new WebSocketServer(new InetSocketAddress(port)) {

                    @Override
                    public void onOpen(WebSocket conn, ClientHandshake handshake) {
                        String clientInfo = conn.getRemoteSocketAddress().toString();
                        Protocol.log("Yeni WebSocket bağlantısı: " + clientInfo);

                        // Maksimum bağlantı kontrolü
                        if (webSocketHandlers.size() >= Protocol.MAX_CONNECTIONS) {
                            Protocol.log("Maksimum bağlantı sayısına ulaşıldı. Reddedilen: " + clientInfo);

                            try {
                                Message errorMsg = Message.createError(null, Protocol.ERROR_MAX_CONNECTIONS);
                                conn.send(errorMsg.serialize());
                                conn.close(1008, "Server full");
                            } catch (Exception e) {
                                Protocol.logError("Hata mesajı gönderilemedi", e);
                            }
                            return;
                        }

                        // ClientHandler oluştur
                        String tempClientId = "ws_temp_" + clientCounter.incrementAndGet();
                        ClientHandler handler = new ClientHandler(Server.this, conn, tempClientId);

                        // Mapping'leri sakla
                        webSocketHandlers.put(conn, handler);
                        conn.setAttachment(handler);

                        Protocol.log("WebSocket ClientHandler oluşturuldu: " + tempClientId);
                    }

                    @Override
                    public void onMessage(WebSocket conn, String message) {
                        try {
                            ClientHandler handler = webSocketHandlers.get(conn);
                            if (handler != null) {
                                handler.onMessage(message);
                            } else {
                                Protocol.log("WARNING: Handler bulunamadı WebSocket için: " + conn.getRemoteSocketAddress());
                            }
                        } catch (Exception e) {
                            Protocol.logError("WebSocket mesaj işleme hatası", e);

                            try {
                                Message errorMsg = Message.createError(null, "Mesaj işleme hatası");
                                conn.send(errorMsg.serialize());
                            } catch (Exception ex) {
                                Protocol.logError("Hata mesajı gönderilemedi", ex);
                            }
                        }
                    }

                    @Override
                    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
                        ClientHandler handler = webSocketHandlers.remove(conn);

                        if (handler != null) {
                            Protocol.log("WebSocket bağlantısı kapandı: " + handler.getUserId() +
                                    " - Code: " + code + ", Reason: " + reason + ", Remote: " + remote);

                            handler.onClose(code, reason, remote);

                            // Eğer authenticated ise connectedClients'tan da kaldır
                            if (handler.isAuthenticated()) {
                                unregisterClient(handler.getUserId());
                            }
                        } else {
                            Protocol.log("WebSocket kapandı (handler bulunamadı): " +
                                    conn.getRemoteSocketAddress() + " - Code: " + code);
                        }
                    }

                    @Override
                    public void onError(WebSocket conn, Exception ex) {
                        ClientHandler handler = webSocketHandlers.get(conn);

                        if (handler != null) {
                            Protocol.logError("WebSocket hatası: " + handler.getUserId(), ex);
                            handler.onError(ex);
                        } else {
                            Protocol.logError("WebSocket hatası (handler yok): " +
                                    (conn != null ? conn.getRemoteSocketAddress() : "null"), ex);
                        }
                    }

                    @Override
                    public void onStart() {
                        Protocol.log("=== WEBSOCKET MTP SERVER BAŞLATILDI ===");
                        Protocol.log("Port: " + port);
                        Protocol.log("Maksimum bağlantı: " + Protocol.MAX_CONNECTIONS);
                        Protocol.log("Documents klasörü: " + Protocol.DOCUMENTS_FOLDER);
                        Protocol.log("Kayıtlı kullanıcı sayısı: " + userManager.getTotalUserCount());
                        Protocol.log("WebSocket Server dinleme modunda...");
                    }
                };

                // Server'ı başlat
                wsServer.start();
                isRunning = true;

            } catch (Exception e) {
                Protocol.logError("WebSocket Server başlatma hatası", e);
                throw e;
            }
        }
    }

    // === CLIENT MANAGEMENT ===

    /**
     * İstemciyi kaydet
     */
    public boolean registerClient(String userId, ClientHandler clientHandler) {
        if (userId == null || clientHandler == null) {
            return false;
        }

        if (connectedClients.containsKey(userId)) {
            Protocol.log("Kullanıcı zaten bağlı: " + userId);
            return false;
        }

        if (!userManager.isValidSession(userId)) {
            Protocol.log("Geçersiz session: " + userId);
            return false;
        }

        connectedClients.put(userId, clientHandler);
        Protocol.log("WebSocket İstemci kaydedildi: " + userId + " (Toplam: " + connectedClients.size() + ")");

        // Welcome mesajı gönder
        sendWelcomeMessage(clientHandler);

        return true;
    }

    /**
     * İstemciyi kaldır
     */
    public void unregisterClient(String userId) {
        if (userId != null && connectedClients.remove(userId) != null) {
            Protocol.log("WebSocket İstemci kaldırıldı: " + userId + " (Toplam: " + connectedClients.size() + ")");
        }
    }

    /**
     * Welcome mesajı gönder
     */
    private void sendWelcomeMessage(ClientHandler clientHandler) {
        try {
            String welcomeText = String.format(
                    "WebSocket sunucusuna hos geldiniz! Server: %s v%s | Aktif kullanici: %d | Toplam dosya: %d",
                    Protocol.PROJECT_NAME,
                    Protocol.VERSION,
                    connectedClients.size(),
                    documentManager.getAllDocuments().size()
            );

            Message welcomeMsg = new Message(Message.MessageType.LOGIN, null, null)
                    .addData("message", welcomeText);
            clientHandler.sendMessage(welcomeMsg);

        } catch (Exception e) {
            Protocol.logError("WebSocket Welcome mesajı gönderilemedi", e);
        }
    }

    // === BROADCASTING ===

    /**
     * Belirli dosyayı düzenleyen kullanıcılara broadcast
     */
    public void broadcastToFile(String fileId, Message message, String excludeUserId) {
        if (fileId == null || message == null) {
            return;
        }

        List<String> fileUsers = documentManager.getFileUsers(fileId);
        int sentCount = 0;

        for (String userId : fileUsers) {
            if (userId.equals(excludeUserId)) {
                continue;
            }

            ClientHandler client = connectedClients.get(userId);
            if (client != null && client.isConnected()) {
                client.sendMessage(message);
                sentCount++;
            }
        }

        if (sentCount > 0) {
            Protocol.log("WebSocket Dosya broadcast: " + fileId + " -> " + sentCount + " kullanıcı");
        }
    }

    /**
     * Tüm bağlı kullanıcılara broadcast
     */
    public void broadcastToAll(Message message, String excludeUserId) {
        if (message == null) {
            return;
        }

        int sentCount = 0;

        for (Map.Entry<String, ClientHandler> entry : connectedClients.entrySet()) {
            String userId = entry.getKey();
            ClientHandler client = entry.getValue();

            if (userId.equals(excludeUserId)) {
                continue;
            }

            if (client.isConnected()) {
                client.sendMessage(message);
                sentCount++;
            }
        }

        if (sentCount > 0) {
            Protocol.log("WebSocket Global broadcast -> " + sentCount + " kullanıcı");
        }
    }

    /**
     * Belirli kullanıcıya mesaj gönder
     */
    public boolean sendToUser(String userId, Message message) {
        if (userId == null || message == null) {
            return false;
        }

        ClientHandler client = connectedClients.get(userId);
        if (client != null && client.isConnected()) {
            client.sendMessage(message);
            return true;
        }

        return false;
    }

    /**
     * Sistem duyurusu gönder
     */
    public void broadcastAnnouncement(String announcement) {
        if (announcement == null || announcement.trim().isEmpty()) {
            return;
        }

        Message announcementMsg = Message.createError(null, "DUYURU: " + announcement);
        broadcastToAll(announcementMsg, null);

        Protocol.log("Sistem duyurusu gönderildi: " + announcement);
    }

    // === USER MANAGEMENT ===

    public List<String> getActiveUsernames() {
        List<String> usernames = new ArrayList<>();

        for (String userId : connectedClients.keySet()) {
            String username = userManager.getUsernameByUserId(userId);
            if (username != null) {
                usernames.add(username);
            }
        }

        return usernames;
    }

    public boolean kickUser(String username, String reason) {
        String userId = userManager.getUserIdByUsername(username);
        if (userId == null) {
            return false;
        }

        ClientHandler client = connectedClients.get(userId);
        if (client != null) {
            Message kickMsg = Message.createError(userId, "Sunucudan çıkarıldınız: " + reason);
            client.sendMessage(kickMsg);

            client.disconnect();

            Protocol.log("Kullanıcı çıkarıldı: " + username + " - " + reason);
            return true;
        }

        return false;
    }

    // === SERVER INFO ===

    public int getConnectedUserCount() {
        return connectedClients.size();
    }

    public List<String> getConnectedUsers() {
        return new ArrayList<>(connectedClients.keySet());
    }

    public boolean isUserConnected(String userId) {
        return userId != null && connectedClients.containsKey(userId);
    }

    public DocumentManager getDocumentManager() {
        return documentManager;
    }

    public UserManager getUserManager() {
        return userManager;
    }

    public boolean isRunning() {
        return isRunning;
    }

    public String getServerStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== WEBSOCKET MTP SERVER İSTATİSTİKLERİ ===\n");
        stats.append("Proje: ").append(Protocol.PROJECT_NAME).append(" v").append(Protocol.VERSION).append("\n");
        stats.append("Transport: WebSocket\n");
        stats.append("Port: ").append(port).append("\n");
        stats.append("Durum: ").append(isRunning ? "Çalışıyor" : "Durdurulmuş").append("\n");
        stats.append("Bağlı Kullanıcılar: ").append(connectedClients.size()).append("/").append(Protocol.MAX_CONNECTIONS).append("\n");

        stats.append("Kayıtlı Kullanıcılar: ").append(userManager.getTotalUserCount()).append("\n");
        stats.append("Aktif Sessionlar: ").append(userManager.getActiveUserCount()).append("\n");

        List<DocumentManager.DocumentInfo> docs = documentManager.getAllDocuments();
        int openFiles = (int) docs.stream().filter(d -> d.getUserCount() > 0).count();
        stats.append("Toplam Dosyalar: ").append(docs.size()).append("\n");
        stats.append("Açık Dosyalar: ").append(openFiles).append("\n");

        stats.append("Çalışma Süresi: ").append(getUptimeString()).append("\n");

        List<String> activeUsernames = getActiveUsernames();
        if (!activeUsernames.isEmpty()) {
            stats.append("Aktif Kullanıcılar: ").append(String.join(", ", activeUsernames)).append("\n");
        }

        stats.append("==============================");

        return stats.toString();
    }

    private String getUptimeString() {
        long uptimeMs = System.currentTimeMillis() - startTime;
        long seconds = uptimeMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        return String.format("%d saat, %d dakika, %d saniye",
                hours, minutes % 60, seconds % 60);
    }

    // === SHUTDOWN ===

    public void stop() {
        synchronized (serverLock) {
            if (!isRunning) {
                Protocol.log("WebSocket Server zaten durdurulmuş!");
                return;
            }

            Protocol.log("=== WEBSOCKET SERVER DURDURULUYOR ===");
            isRunning = false;

            try {
                Message disconnectMsg = Message.createDisconnect(null);
                broadcastToAll(disconnectMsg, null);

                Thread.sleep(1000);

                for (ClientHandler handler : webSocketHandlers.values()) {
                    handler.disconnect();
                }
                webSocketHandlers.clear();
                connectedClients.clear();

                if (wsServer != null) {
                    wsServer.stop(5000);
                }

                if (userManager != null) {
                    userManager.clearAllSessions();
                }

                if (documentManager != null) {
                    documentManager.shutdown();
                }

                Protocol.log("WebSocket Server başarıyla durduruldu.");

            } catch (Exception e) {
                Protocol.logError("WebSocket Server durdurma hatası", e);
            }
        }
    }

    // === MAIN METHOD ===

    public static void main(String[] args) {
        System.out.println("=== " + Protocol.PROJECT_NAME + " WebSocket Server v" + Protocol.VERSION + " ===");

        int port = Protocol.DEFAULT_PORT; // Default 8080 kullan

        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
                if (!Protocol.isValidPort(port)) {
                    throw new NumberFormatException("Port range: 1-65535");
                }
            } catch (NumberFormatException e) {
                System.err.println("Geçersiz port: " + args[0] + " (" + e.getMessage() + ")");
                System.err.println("Varsayılan port kullanılıyor: " + Protocol.DEFAULT_PORT);
                port = Protocol.DEFAULT_PORT;
            }
        }

        Server server = new Server(port);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Protocol.log("Shutdown sinyali alındı (Ctrl+C)...");
            server.stop();
        }));

        Timer statsTimer = new Timer(true);
        statsTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (server.isRunning() && server.getConnectedUserCount() > 0) {
                    Protocol.log("WebSocket Aktif kullanıcı: " + server.getConnectedUserCount() +
                            " | Kayıtlı kullanıcı: " + server.getUserManager().getTotalUserCount());
                }
            }
        }, 60000, 60000);

        Scanner scanner = new Scanner(System.in);
        Thread consoleThread = new Thread(() -> {
            System.out.println("WebSocket Server komutları: 'status', 'stop', 'help'");
            while (server.isRunning()) {
                try {
                    System.out.print("websocket-server> ");
                    String command = scanner.nextLine().trim().toLowerCase();

                    switch (command) {
                        case "status":
                            System.out.println(server.getServerStats());
                            break;
                        case "stop":
                        case "quit":
                        case "exit":
                            Protocol.log("Manuel stop komutu...");
                            server.stop();
                            System.exit(0);
                            break;
                        case "help":
                            System.out.println("Komutlar: status, stop, help");
                            break;
                        default:
                            if (!command.isEmpty()) {
                                System.out.println("Bilinmeyen komut: " + command + " (help yazın)");
                            }
                            break;
                    }
                } catch (Exception e) {
                    // Console hatası - devam et
                }
            }
        });
        consoleThread.setDaemon(true);
        consoleThread.start();

        try {
            server.start();

            while (server.isRunning()) {
                Thread.sleep(1000);
            }

        } catch (Exception e) {
            Protocol.logError("WebSocket Server başlatılamadı", e);
            System.exit(1);
        }
    }
}