package Common;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
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

    public static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return null;
        }

        // Boşlukları temizle
        String cleaned = fileName.trim();

        // Tehlikeli karakterleri kaldır
        cleaned = cleaned.replaceAll("[<>:\"|?*\\\\]", "");

        // Path separator'ları kaldır
        cleaned = cleaned.replaceAll("[/\\\\]", "");

        // Boş string kontrolü
        if (cleaned.isEmpty()) {
            return null;
        }

        return cleaned;
    }

    /**
     * Dosyanın var olup olmadığını kontrol eder
     */
    public static boolean fileExists(String filePath) {
        try {
            File file = new File(filePath);
            boolean exists = file.exists();
            boolean canRead = file.canRead();
            boolean isFile = file.isFile();
            long length = file.length();

            System.out.println("DEBUG: Utils.fileExists() - path: " + filePath);
            System.out.println("DEBUG: file.exists(): " + exists);
            System.out.println("DEBUG: file.canRead(): " + canRead);
            System.out.println("DEBUG: file.isFile(): " + isFile);
            System.out.println("DEBUG: file.length(): " + length);

            return exists && isFile && canRead;

        } catch (Exception e) {
            System.out.println("DEBUG: Utils.fileExists() exception: " + e.getMessage());
            return false;
        }
    }

    /**
     * Dosya içeriğini okur
     */
    public static String readFileContent(String filePath) {
        try {
            System.out.println("DEBUG: Utils.readFileContent() - path: " + filePath);

            File file = new File(filePath);
            if (!file.exists()) {
                System.out.println("DEBUG: File does not exist");
                return null;
            }

            if (!file.canRead()) {
                System.out.println("DEBUG: File cannot be read");
                return null;
            }

            System.out.println("DEBUG: File size: " + file.length() + " bytes");

            // Java 11+ versiyonu
            String content = Files.readString(Paths.get(filePath), StandardCharsets.UTF_8);
            System.out.println("DEBUG: Content read successfully: " + content.length() + " characters");

            return content;

        } catch (Exception e) {
            System.out.println("DEBUG: Utils.readFileContent() exception: " + e.getMessage());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Dosya içeriğini yazar
     */
    public static boolean writeFileContent(String filePath, String content) {
        try {
            System.out.println("DEBUG: Utils.writeFileContent() - path: " + filePath);
            System.out.println("DEBUG: Content length: " + (content != null ? content.length() : "null"));

            Path path = Paths.get(filePath);

            // Klasörü oluştur
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }

            // Dosyayı yaz
            Files.write(path, content.getBytes(StandardCharsets.UTF_8));

            System.out.println("DEBUG: File written successfully");
            return true;

        } catch (Exception e) {
            System.out.println("DEBUG: Utils.writeFileContent() exception: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
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