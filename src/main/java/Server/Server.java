package Server;

import Common.Message;
import Common.Protocol;
import Common.Utils;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * MTP (Multi-user Text Protocol) Ana Sunucu Sınıfı
 * Çok kullanıcılı metin editörü için TCP sunucusu
 */
public class Server {

    // Sunucu yapılandırması
    private final int port;
    private ServerSocket serverSocket;
    private boolean isRunning = false;

    // Thread yönetimi
    private ExecutorService clientThreadPool;
    private final Object serverLock = new Object();

    // İstemci yönetimi
    private final Map<String, ClientHandler> connectedClients = new ConcurrentHashMap<>();
    private final AtomicInteger clientCounter = new AtomicInteger(0);

    // Dokuman yönetimi
    private DocumentManager documentManager;

    /**
     * Constructor - varsayılan port ile
     */
    public Server() {
        this(Protocol.DEFAULT_PORT);
    }

    /**
     * Constructor - özel port ile
     */
    public Server(int port) {
        if (!Protocol.isValidPort(port)) {
            throw new IllegalArgumentException("Geçersiz port: " + port);
        }

        this.port = port;
        this.documentManager = new DocumentManager();

        // Thread pool oluştur
        this.clientThreadPool = Executors.newFixedThreadPool(Protocol.MAX_CONNECTIONS);

        Utils.log("Server oluşturuldu. Port: " + port);
    }

    /**
     * Sunucuyu başlatır
     */
    public void start() throws IOException {
        synchronized (serverLock) {
            if (isRunning) {
                Utils.log("Server zaten çalışıyor!");
                return;
            }

            try {
                // Server socket oluştur
                serverSocket = new ServerSocket(port);
                serverSocket.setReuseAddress(true);

                isRunning = true;
                Utils.log("Server başlatıldı. Port: " + port);
                Utils.log("Maksimum bağlantı: " + Protocol.MAX_CONNECTIONS);

                // Ana server loop'u
                acceptClients();

            } catch (IOException e) {
                Utils.logError("Server başlatma hatası", e);
                throw e;
            }
        }
    }

    /**
     * İstemci bağlantılarını kabul eden ana loop
     */
    private void acceptClients() {
        Utils.log("İstemci bağlantıları dinleniyor...");

        while (isRunning && !serverSocket.isClosed()) {
            try {
                // Yeni bağlantıyı kabul et
                Socket clientSocket = serverSocket.accept();

                // Maksimum bağlantı kontrolü
                if (connectedClients.size() >= Protocol.MAX_CONNECTIONS) {
                    Utils.log("Maksimum bağlantı sayısına ulaşıldı. Bağlantı reddedildi: " +
                            clientSocket.getInetAddress());

                    sendMaxConnectionError(clientSocket);
                    clientSocket.close();
                    continue;
                }

                // İstemci bilgilerini log'la
                String clientIP = clientSocket.getInetAddress().getHostAddress();
                int clientPort = clientSocket.getPort();
                Utils.log("Yeni bağlantı: " + clientIP + ":" + clientPort);

                // ClientHandler oluştur ve thread pool'a ekle
                String tempClientId = "temp_" + clientCounter.incrementAndGet();
                ClientHandler clientHandler = new ClientHandler(this, clientSocket, tempClientId);

                clientThreadPool.submit(clientHandler);

            } catch (SocketException e) {
                // Server kapatılırken normal bir durum
                if (isRunning) {
                    Utils.logError("Socket hatası", e);
                }
            } catch (IOException e) {
                Utils.logError("İstemci kabul etme hatası", e);
            }
        }
    }

    /**
     * Maksimum bağlantı hatası gönderir
     */
    private void sendMaxConnectionError(Socket clientSocket) {
        try {
            Message errorMsg = Message.createErrorMessage(null,
                    Protocol.ERROR_MAX_CONNECTIONS,
                    Protocol.getErrorMessage(Protocol.ERROR_MAX_CONNECTIONS));

            Utils.writeToSocket(clientSocket, errorMsg.serialize());
        } catch (IOException e) {
            Utils.logError("Hata mesajı gönderilemedi", e);
        }
    }

    /**
     * İstemciyi sunucuya kaydet
     */
    public boolean registerClient(String userId, ClientHandler clientHandler) {
        if (userId == null || clientHandler == null) {
            return false;
        }

        // Kullanıcı zaten bağlı mı kontrol et
        if (connectedClients.containsKey(userId)) {
            Utils.log("Kullanıcı zaten bağlı: " + userId);
            return false;
        }

        connectedClients.put(userId, clientHandler);
        Utils.log("İstemci kaydedildi: " + userId + " (Toplam: " + connectedClients.size() + ")");

        return true;
    }

    /**
     * İstemciyi sunucudan kaldır
     */
    public void unregisterClient(String userId) {
        if (userId != null && connectedClients.remove(userId) != null) {
            Utils.log("İstemci kaldırıldı: " + userId + " (Toplam: " + connectedClients.size() + ")");
        }
    }

    /**
     * Belirli bir dosyayı düzenleyen tüm kullanıcılara mesaj gönder
     */
    public void broadcastToFile(String fileId, Message message, String excludeUserId) {
        if (fileId == null || message == null) {
            return;
        }

        List<String> fileUsers = documentManager.getFileUsers(fileId);
        int sentCount = 0;

        for (String userId : fileUsers) {
            if (userId.equals(excludeUserId)) {
                continue; // Gönderen kullanıcıyı hariç tut
            }

            ClientHandler client = connectedClients.get(userId);
            if (client != null) {
                client.sendMessage(message);
                sentCount++;
            }
        }

        Utils.log("Dosya broadcast: " + fileId + " -> " + sentCount + " kullanıcı");
    }

    /**
     * Tüm bağlı kullanıcılara mesaj gönder
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
                continue; // Gönderen kullanıcıyı hariç tut
            }

            client.sendMessage(message);
            sentCount++;
        }

        Utils.log("Global broadcast -> " + sentCount + " kullanıcı");
    }

    /**
     * Belirli kullanıcıya mesaj gönder
     */
    public boolean sendToUser(String userId, Message message) {
        if (userId == null || message == null) {
            return false;
        }

        ClientHandler client = connectedClients.get(userId);
        if (client != null) {
            client.sendMessage(message);
            return true;
        }

        return false;
    }

    /**
     * Bağlı kullanıcı sayısını döndür
     */
    public int getConnectedUserCount() {
        return connectedClients.size();
    }

    /**
     * Bağlı kullanıcı listesini döndür
     */
    public List<String> getConnectedUsers() {
        return new ArrayList<>(connectedClients.keySet());
    }

    /**
     * Belirli kullanıcının bağlı olup olmadığını kontrol et
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
     * Sunucuyu durdur
     */
    public void stop() {
        synchronized (serverLock) {
            if (!isRunning) {
                Utils.log("Server zaten durdurulmuş!");
                return;
            }

            Utils.log("Server durduruluyor...");
            isRunning = false;

            try {
                // Tüm istemcilere disconnect mesajı gönder
                Message disconnectMsg = new Message(Message.MessageType.DISCONNECT,
                        null, null, "{}");
                broadcastToAll(disconnectMsg, null);

                // Socket'i kapat
                if (serverSocket != null && !serverSocket.isClosed()) {
                    serverSocket.close();
                }

                // Thread pool'u kapat
                if (clientThreadPool != null) {
                    clientThreadPool.shutdown();

                    // 5 saniye bekle
                    if (!clientThreadPool.awaitTermination(5, TimeUnit.SECONDS)) {
                        Utils.log("Thread pool zorla kapatılıyor...");
                        clientThreadPool.shutdownNow();
                    }
                }

                // Tüm istemci bağlantılarını kapat
                for (ClientHandler client : connectedClients.values()) {
                    client.disconnect();
                }
                connectedClients.clear();

                Utils.log("Server başarıyla durduruldu.");

            } catch (Exception e) {
                Utils.logError("Server durdurma hatası", e);
            }
        }
    }

    /**
     * Sunucunun çalışıp çalışmadığını kontrol et
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * Server istatistiklerini döndür
     */
    public String getServerStats() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== SERVER İSTATİSTİKLERİ ===\n");
        stats.append("Port: ").append(port).append("\n");
        stats.append("Durum: ").append(isRunning ? "Çalışıyor" : "Durdurulmuş").append("\n");
        stats.append("Bağlı Kullanıcılar: ").append(connectedClients.size()).append("/").append(Protocol.MAX_CONNECTIONS).append("\n");
        stats.append("Açık Dosyalar: ").append(documentManager.getOpenFileCount()).append("\n");
        stats.append("Memory Kullanımı: ").append(Utils.getMemoryUsage()).append(" MB\n");
        stats.append("Çalışma Süresi: ").append(getUptimeString()).append("\n");

        return stats.toString();
    }

    private long startTime = System.currentTimeMillis();

    private String getUptimeString() {
        long uptimeMs = System.currentTimeMillis() - startTime;
        long seconds = uptimeMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        return String.format("%d saat, %d dakika, %d saniye",
                hours, minutes % 60, seconds % 60);
    }

    /**
     * Ana method - sunucuyu başlatır
     */
    public static void main(String[] args) {
        int port = Protocol.DEFAULT_PORT;

        // Komut satırından port al
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Geçersiz port numarası: " + args[0]);
                System.err.println("Varsayılan port kullanılıyor: " + Protocol.DEFAULT_PORT);
            }
        }

        Server server = new Server(port);

        // Graceful shutdown için shutdown hook ekle
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Utils.log("Shutdown sinyali alındı...");
            server.stop();
        }));

        try {
            server.start();
        } catch (IOException e) {
            Utils.logError("Server başlatılamadı", e);
            System.exit(1);
        }
    }
}
