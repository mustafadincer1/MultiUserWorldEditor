package Common;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;

/**
 * MTP projesi için minimal utility metotları
 * Sadece gerçekten gerekli olan network, file ve logging işlemleri
 */
public final class Utils {

    // Constructor'ı private yap
    private Utils() {
        throw new AssertionError("Utils sınıfı instantiate edilemez!");
    }

    // === NETWORK UTILITIES ===

    /**
     * Socket'ten güvenli şekilde bir satır okur
     */
    public static String readFromSocket(Socket socket) throws IOException {
        if (socket == null || socket.isClosed()) {
            throw new IOException("Socket kapalı veya null");
        }

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), "UTF-8"));

        return reader.readLine();
    }

    /**
     * Socket'e güvenli şekilde mesaj yazar
     */
    public static void writeToSocket(Socket socket, String message) throws IOException {
        if (socket == null || socket.isClosed()) {
            throw new IOException("Socket kapalı veya null");
        }

        if (message == null) {
            throw new IllegalArgumentException("Mesaj null olamaz");
        }

        PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);

        // Mesajın sonunda \n yoksa ekle
        if (!message.endsWith("\n")) {
            message += "\n";
        }

        writer.print(message);
        writer.flush();
    }

    // === FILE UTILITIES ===

    /**
     * Dosyanın var olup olmadığını kontrol eder
     */
    public static boolean fileExists(String filePath) {
        return Files.exists(Paths.get(filePath));
    }

    /**
     * Dosya içeriğini okur
     */
    public static String readFileContent(String filePath) throws IOException {
        Path path = Paths.get(filePath);

        if (!Files.exists(path)) {
            throw new FileNotFoundException("Dosya bulunamadı: " + filePath);
        }

        // Dosya boyutu kontrolü
        if (Files.size(path) > Protocol.MAX_FILE_SIZE) {
            throw new IOException("Dosya boyutu çok büyük: " + Files.size(path));
        }

        return new String(Files.readAllBytes(path), "UTF-8");
    }

    /**
     * Dosya içeriğini yazar
     */
    public static void writeFileContent(String filePath, String content) throws IOException {
        if (content == null) {
            content = "";
        }

        Path path = Paths.get(filePath);

        // Klasör yoksa oluştur
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }

        Files.write(path, content.getBytes("UTF-8"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    // === LOGGING UTILITIES ===

    /**
     * Console'a log yazar
     */
    public static void log(String message) {
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.out.println("[" + timestamp + "] " + message);
    }

    /**
     * Hata log'u yazar
     */
    public static void logError(String message, Throwable throwable) {
        String timestamp = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        System.err.println("[" + timestamp + "] ERROR: " + message);

        if (throwable != null) {
            throwable.printStackTrace();
        }
    }
}