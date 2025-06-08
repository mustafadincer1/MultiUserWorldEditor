package Server;

import Common.*;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MTP (Multi-user Text Protocol) Ana Sunucu
 * Robust OT entegreli DocumentManager ve UserManager ile çalışır
 */
public class Server {

    // Server konfigürasyonu
    private final int port;
    private ServerSocket serverSocket;
    private boolean isRunning = false;

    // Thread yönetimi
    private ExecutorService clientThreadPool;
    private final Object serverLock = new Object();

    // İstemci yönetimi
    private final Map<String, ClientHandler> connectedClients = new ConcurrentHashMap<>();
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
        this(Protocol.DEFAULT_PORT);
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
        this.clientThreadPool = Executors.newFixedThreadPool(Protocol.MAX_CONNECTIONS);

        Protocol.log("Server oluşturuldu. Port: " + port);
    }

    /**
     * Sunucuyu başlat
     */
    public void start() throws IOException {
        synchronized (serverLock) {
            if (isRunning) {
                Protocol.log("Server zaten çalışıyor!");
                return;
            }

            try {
                // Server socket oluştur
                serverSocket = new ServerSocket(port);
                serverSocket.setReuseAddress(true);

                isRunning = true;
                Protocol.log("=== MTP SERVER BAŞLATILDI ===");
                Protocol.log("Port: " + port);
                Protocol.log("Maksimum bağlantı: " + Protocol.MAX_CONNECTIONS);
                Protocol.log("Documents klasörü: " + Protocol.DOCUMENTS_FOLDER);
                Protocol.log("Kayıtlı kullanıcı sayısı: " + userManager.getTotalUserCount());

                // Ana server loop
                acceptClients();

            } catch (IOException e) {
                Protocol.logError("Server başlatma hatası", e);
                throw e;
            }
        }
    }

    /**
     * İstemci bağlantılarını kabul eden ana loop
     */
    private void acceptClients() {
        Protocol.log("İstemci bağlantıları dinleniyor...");

        while (isRunning && !serverSocket.isClosed()) {
            try {
                // Yeni bağlantıyı kabul et
                Socket clientSocket = serverSocket.accept();

                // Maksimum bağlantı kontrolü
                if (connectedClients.size() >= Protocol.MAX_CONNECTIONS) {
                    Protocol.log("Maksimum bağlantı sayısına ulaşıldı. Reddedilen: " +
                            clientSocket.getInetAddress());

                    sendMaxConnectionError(clientSocket);
                    clientSocket.close();
                    continue;
                }

                // İstemci bilgileri
                String clientInfo = clientSocket.getInetAddress().getHostAddress() + ":" +
                        clientSocket.getPort();
                Protocol.log("Yeni bağlantı: " + clientInfo);

                // ClientHandler oluştur ve başlat
                String tempClientId = "temp_" + clientCounter.incrementAndGet();
                ClientHandler clientHandler = new ClientHandler(this, clientSocket, tempClientId);

                clientThreadPool.submit(clientHandler);

            } catch (SocketException e) {
                // Server kapatılırken normal
                if (isRunning) {
                    Protocol.logError("Socket hatası", e);
                }
            } catch (IOException e) {
                Protocol.logError("İstemci kabul etme hatası", e);
            }
        }
    }

    /**
     * Maksimum bağlantı hatası gönder
     */
    private void sendMaxConnectionError(Socket clientSocket) {
        try {
            Message errorMsg = Message.createError(null, Protocol.ERROR_MAX_CONNECTIONS);
            Utils.writeToSocket(clientSocket, errorMsg.serialize());
        } catch (IOException e) {
            Protocol.logError("Hata mesajı gönderilemedi", e);
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

        // Kullanıcı zaten bağlı mı?
        if (connectedClients.containsKey(userId)) {
            Protocol.log("Kullanıcı zaten bağlı: " + userId);
            return false;
        }

        // UserManager'da session var mı?
        if (!userManager.isValidSession(userId)) {
            Protocol.log("Geçersiz session: " + userId);
            return false;
        }

        connectedClients.put(userId, clientHandler);
        Protocol.log("İstemci kaydedildi: " + userId + " (Toplam: " + connectedClients.size() + ")");

        // Welcome mesajı gönder
        sendWelcomeMessage(clientHandler);

        return true;
    }

    /**
     * İstemciyi kaldır
     */
    public void unregisterClient(String userId) {
        if (userId != null && connectedClients.remove(userId) != null) {
            Protocol.log("İstemci kaldırıldı: " + userId + " (Toplam: " + connectedClients.size() + ")");
        }
    }

    /**
     * Welcome mesajı gönder
     */
    private void sendWelcomeMessage(ClientHandler clientHandler) {
        try {
            String welcomeText = String.format(
                    "Hoş geldiniz! Server: %s v%s | Aktif kullanıcı: %d | Toplam dosya: %d",
                    Protocol.PROJECT_NAME,
                    Protocol.VERSION,
                    connectedClients.size(),
                    documentManager.getAllDocuments().size()
            );

            // Sistem mesajı olarak gönder
            Message welcomeMsg = new Message(Message.MessageType.ERROR, null, null)
                    .addData("message", welcomeText);
            clientHandler.sendMessage(welcomeMsg);

        } catch (Exception e) {
            Protocol.logError("Welcome mesajı gönderilemedi", e);
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
                continue; // Gönderen hariç
            }

            ClientHandler client = connectedClients.get(userId);
            if (client != null && client.isConnected()) {
                client.sendMessage(message);
                sentCount++;
            }
        }

        if (sentCount > 0) {
            Protocol.log("Dosya broadcast: " + fileId + " -> " + sentCount + " kullanıcı");
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
                continue; // Gönderen hariç
            }

            if (client.isConnected()) {
                client.sendMessage(message);
                sentCount++;
            }
        }

        if (sentCount > 0) {
            Protocol.log("Global broadcast -> " + sentCount + " kullanıcı");
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

    /**
     * Aktif kullanıcı listesi (username ile)
     */
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

    /**
     * Kullanıcıyı zorla çıkar
     */
    public boolean kickUser(String username, String reason) {
        String userId = userManager.getUserIdByUsername(username);
        if (userId == null) {
            return false;
        }

        ClientHandler client = connectedClients.get(userId);
        if (client != null) {
            // Kick mesajı gönder
            Message kickMsg = Message.createError(userId, "Sunucudan çıkarıldınız: " + reason);
            client.sendMessage(kickMsg);

            // Bağlantıyı kapat
            client.disconnect();

            Protocol.log("Kullanıcı çıkarıldı: " + username + " - " + reason);
            return true;
        }

        return false;
    }

    // === SERVER INFO ===

    /**
     * Bağlı kullanıcı sayısı
     */
    public int getConnectedUserCount() {
        return connectedClients.size();
    }

    /**
     * Bağlı kullanıcı listesi
     */
    public List<String> getConnectedUsers() {
        return new ArrayList<>(connectedClients.keySet());
    }

    /**
     * Kullanıcının bağlı olup olmadığını kontrol et
     */
    public boolean isUserConnected(String userId) {
        return userId != null && connectedClients.containsKey(userId);
    }

    /**
     * DocumentManager'a erişim
     */
    public DocumentManager getDocumentManager() {
        return documentManager;
    }

    /**
     * UserManager'a erişim
     */
    public UserManager getUserManager() {
        return userManager;
    }

    /**
     * Sunucunun çalışıp çalışmadığını kontrol et
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Server istatistikleri
     */
    public String getServerStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== MTP SERVER İSTATİSTİKLERİ ===\n");
        stats.append("Proje: ").append(Protocol.PROJECT_NAME).append(" v").append(Protocol.VERSION).append("\n");
        stats.append("Port: ").append(port).append("\n");
        stats.append("Durum: ").append(isRunning ? "Çalışıyor" : "Durdurulmuş").append("\n");
        stats.append("Bağlı Kullanıcılar: ").append(connectedClients.size()).append("/").append(Protocol.MAX_CONNECTIONS).append("\n");

        // User istatistikleri
        stats.append("Kayıtlı Kullanıcılar: ").append(userManager.getTotalUserCount()).append("\n");
        stats.append("Aktif Sessionlar: ").append(userManager.getActiveUserCount()).append("\n");

        // Document istatistikleri
        List<DocumentManager.DocumentInfo> docs = documentManager.getAllDocuments();
        int openFiles = (int) docs.stream().filter(d -> d.getUserCount() > 0).count();
        stats.append("Toplam Dosyalar: ").append(docs.size()).append("\n");
        stats.append("Açık Dosyalar: ").append(openFiles).append("\n");

        stats.append("Çalışma Süresi: ").append(getUptimeString()).append("\n");

        // Aktif kullanıcı isimleri
        List<String> activeUsernames = getActiveUsernames();
        if (!activeUsernames.isEmpty()) {
            stats.append("Aktif Kullanıcılar: ").append(String.join(", ", activeUsernames)).append("\n");
        }

        stats.append("==============================");

        return stats.toString();
    }

    /**
     * Uptime hesapla
     */
    private String getUptimeString() {
        long uptimeMs = System.currentTimeMillis() - startTime;
        long seconds = uptimeMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        return String.format("%d saat, %d dakika, %d saniye",
                hours, minutes % 60, seconds % 60);
    }




    // === SHUTDOWN ===

    /**
     * Sunucuyu güvenli şekilde durdur
     */
    public void stop() {
        synchronized (serverLock) {
            if (!isRunning) {
                Protocol.log("Server zaten durdurulmuş!");
                return;
            }

            Protocol.log("=== SERVER DURDURULUYOR ===");
            isRunning = false;

            try {
                // Tüm istemcilere disconnect bildir
                Message disconnectMsg = Message.createDisconnect(null);
                broadcastToAll(disconnectMsg, null);

                // Kısa bekle (mesajların iletilmesi için)
                Thread.sleep(1000);

                // Socket'i kapat
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }

                // Thread pool'u kapat
                if (clientThreadPool != null) {
                    clientThreadPool.shutdown();

                    if (!clientThreadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                        Protocol.log("Thread pool zorla kapatılıyor...");
                        clientThreadPool.shutdownNow();
                    }
                }

                // Tüm client bağlantılarını kapat
                for (ClientHandler client : connectedClients.values()) {
                    client.disconnect();
                }
                connectedClients.clear();

                // Manager'ları kapat
                if (userManager != null) {
                    userManager.clearAllSessions();
                }

                if (documentManager != null) {
                    documentManager.shutdown();
                }

                Protocol.log("Server başarıyla durduruldu.");

            } catch (Exception e) {
                Protocol.logError("Server durdurma hatası", e);
            }
        }
    }

    // === MAIN METHOD ===

    /**
     * Ana method - sunucuyu başlat
     */
    public static void main(String[] args) {
        System.out.println("=== " + Protocol.PROJECT_NAME + " v" + Protocol.VERSION + " ===");

        int port = Protocol.DEFAULT_PORT;

        // Komut satırından port al
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

        // Graceful shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Protocol.log("Shutdown sinyali alındı (Ctrl+C)...");
            server.stop();
        }));

        // Server istatistiklerini periyodik göster
        Timer statsTimer = new Timer(true);
        statsTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (server.isRunning() && server.getConnectedUserCount() > 0) {
                    Protocol.log("Aktif kullanıcı: " + server.getConnectedUserCount() +
                            " | Kayıtlı kullanıcı: " + server.getUserManager().getTotalUserCount());
                }
            }
        }, 60000, 60000); // Her dakika

        // Console commands için basit scanner
        Scanner scanner = new Scanner(System.in);
        Thread consoleThread = new Thread(() -> {
            System.out.println("Server komutları için 'help' yazın");
            while (server.isRunning()) {
                try {
                    System.out.print("server> ");
                    String command = scanner.nextLine();
                } catch (Exception e) {
                    // Console hatası - devam et
                }
            }
        });
        consoleThread.setDaemon(true);
        consoleThread.start();

        try {
            server.start();
        } catch (IOException e) {
            Protocol.logError("Server başlatılamadı", e);
            System.exit(1);
        }
    }
}