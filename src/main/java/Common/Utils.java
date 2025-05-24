package Common;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * MTP projesi için yardımcı metotlar
 * Network, dosya işlemleri, logging, validation vs. için utility metotlar
 */

/**
 * MTP projesi için yardımcı metotlar
 * Network, dosya işlemleri, logging, validation vs. için utility metotlar
 */
public final class Utils {

    // Constructor'ı private yap - utility class
    private Utils() {
        throw new AssertionError("Utils sınıfı instantiate edilemez!");
    }

    // === ID GENERATION ===

    private static final AtomicLong idCounter = new AtomicLong(1);

    /**
     * Thread-safe unique ID üretir
     */
    public static String generateUniqueId(String prefix) {
        long timestamp = System.currentTimeMillis();
        long counter = idCounter.getAndIncrement();
        return String.format("%s%d_%d", prefix, timestamp, counter);
    }

    /**
     * Random string üretir (session ID, file ID vs. için)
     */
    public static String generateRandomString(int length) {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder();
        Random random = new Random();

        for (int i = 0; i < length; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }

        return sb.toString();
    }

    // === NETWORK UTILITIES ===

    /**
     * Socket'ten güvenli şekilde veri okur
     */
    public static String readFromSocket(Socket socket) throws IOException {
        if (socket == null || socket.isClosed()) {
            throw new IOException("Socket kapalı veya null");
        }

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(socket.getInputStream(), "UTF-8"));

        // Timeout ile okuma
        socket.setSoTimeout(Protocol.SOCKET_READ_TIMEOUT);

        // Tek satır oku (\n'e kadar)
        String line = reader.readLine();

        if (line != null) {
            System.out.println("DEBUG: readFromSocket okudu: '" + line + "'"); // DEBUG
            return line + Protocol.MESSAGE_END; // \n ekle
        }

        return null;
    }

    /**
     * Socket'e güvenli şekilde veri yazar
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
        if (!message.endsWith(Protocol.MESSAGE_END)) {
            message += Protocol.MESSAGE_END;
        }

        writer.print(message);
        writer.flush();
    }

    /**
     * Socket'i güvenli şekilde kapatır
     */
    public static void closeSocket(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException e) {
                log("Socket kapatma hatası: " + e.getMessage());
            }
        }
    }

    /**
     * IP adresinin geçerli olup olmadığını kontrol eder
     */
    public static boolean isValidIP(String ip) {
        if (ip == null || ip.trim().isEmpty()) {
            return false;
        }

        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }

        try {
            for (String part : parts) {
                int num = Integer.parseInt(part);
                if (num < 0 || num > 255) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Port numarasının geçerli olup olmadığını kontrol eder
     */
    public static boolean isValidPort(int port) {
        return port > 0 && port <= 65535;
    }

    // === FILE UTILITIES ===

    /**
     * Dosyayı güvenli şekilde okur
     */
    public static String readFileContent(String filePath) throws IOException {
        Path path = Paths.get(filePath);

        if (!Files.exists(path)) {
            throw new FileNotFoundException("Dosya bulunamadı: " + filePath);
        }

        if (Files.size(path) > Protocol.MAX_FILE_SIZE) {
            throw new IOException("Dosya boyutu çok büyük: " + Files.size(path));
        }

        return new String(Files.readAllBytes(path), "UTF-8");
    }

    /**
     * Dosyayı güvenli şekilde yazar
     */
    public static void writeFileContent(String filePath, String content) throws IOException {
        if (content == null) {
            content = "";
        }

        Path path = Paths.get(filePath);

        // Klasör yoksa oluştur
        Files.createDirectories(path.getParent());

        Files.write(path, content.getBytes("UTF-8"),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING);
    }

    /**
     * Dosyanın var olup olmadığını kontrol eder
     */
    public static boolean fileExists(String filePath) {
        return Files.exists(Paths.get(filePath));
    }

    /**
     * Dosyayı güvenli şekilde siler
     */
    public static boolean deleteFile(String filePath) {
        try {
            return Files.deleteIfExists(Paths.get(filePath));
        } catch (IOException e) {
            log("Dosya silme hatası: " + e.getMessage());
            return false;
        }
    }

    /**
     * Klasördeki tüm dosyaları listeler
     */
    public static List<String> listFiles(String dirPath) throws IOException {
        List<String> files = new ArrayList<>();
        Path dir = Paths.get(dirPath);

        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
            return files;
        }

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    files.add(file.getFileName().toString());
                }
            }
        }

        return files;
    }

    /**
     * Dosya uzantısını döndürür
     */
    public static String getFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf("."));
    }

    /**
     * Dosya isminden uzantıyı çıkarır
     */
    public static String removeFileExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return filename;
        }
        return filename.substring(0, filename.lastIndexOf("."));
    }

    // === STRING UTILITIES ===

    /**
     * JSON string escape
     */
    public static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    /**
     * String'in null veya boş olup olmadığını kontrol eder
     */
    public static boolean isNullOrEmpty(String str) {
        return str == null || str.trim().isEmpty();
    }

    /**
     * String'i güvenli şekilde trim eder
     */
    public static String safeTrim(String str) {
        return str == null ? "" : str.trim();
    }

    /**
     * String'in maksimum uzunluğunu kontrol eder
     */
    public static String limitString(String str, int maxLength) {
        if (str == null) return "";
        return str.length() > maxLength ? str.substring(0, maxLength) : str;
    }

    /**
     * String'i HTML-safe hale getirir
     */
    public static String escapeHtml(String str) {
        if (str == null) return "";

        return str.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#x27;");
    }

    /**
     * Metin içindeki satır sayısını hesaplar
     */
    public static int countLines(String text) {
        if (isNullOrEmpty(text)) return 0;
        return text.split("\n").length;
    }

    // === VALIDATION UTILITIES ===

    /**
     * Text pozisyonunun metin içinde geçerli olup olmadığını kontrol eder
     */
    public static boolean isValidPosition(String text, int position) {
        if (text == null) return position == 0;
        return position >= 0 && position <= text.length();
    }

    /**
     * Delete operasyonunun geçerli olup olmadığını kontrol eder
     */
    public static boolean isValidDeleteOperation(String text, int position, int length) {
        if (text == null || position < 0 || length <= 0) {
            return false;
        }
        return position + length <= text.length();
    }

    /**
     * Email formatının geçerli olup olmadığını kontrol eder (bonus)
     */
    public static boolean isValidEmail(String email) {
        if (isNullOrEmpty(email)) return false;

        String emailRegex = "^[a-zA-Z0-9_+&*-]+(?:\\.[a-zA-Z0-9_+&*-]+)*@" +
                "(?:[a-zA-Z0-9-]+\\.)+[a-zA-Z]{2,7}$";

        return email.matches(emailRegex);
    }

    // === LOGGING UTILITIES ===

    private static final SimpleDateFormat dateFormat =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    /**
     * Console'a log yazar
     */
    public static void log(String message) {
        if (Protocol.DEBUG_MODE) {
            String timestamp = dateFormat.format(new Date());
            System.out.println("[" + timestamp + "] " + message);
        }
    }

    /**
     * Hata log'u yazar
     */
    public static void logError(String message, Throwable throwable) {
        String timestamp = dateFormat.format(new Date());
        System.err.println("[" + timestamp + "] ERROR: " + message);

        if (throwable != null && Protocol.VERBOSE_LOGGING) {
            throwable.printStackTrace();
        }
    }

    /**
     * Dosyaya log yazar
     */
    public static void logToFile(String message) {
        try {
            String timestamp = dateFormat.format(new Date());
            String logEntry = "[" + timestamp + "] " + message + "\n";

            Files.write(Paths.get(Protocol.LOG_FILE),
                    logEntry.getBytes("UTF-8"),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            System.err.println("Log dosyası yazma hatası: " + e.getMessage());
        }
    }

    // === SECURITY UTILITIES ===

    /**
     * String'in MD5 hash'ini hesaplar
     */
    public static String calculateMD5(String input) {
        if (input == null) return "";

        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(input.getBytes("UTF-8"));

            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }

            return sb.toString();
        } catch (NoSuchAlgorithmException | IOException e) {
            logError("MD5 hesaplama hatası", e);
            return "";
        }
    }

    /**
     * Dosya içeriğinin checksum'ını hesaplar
     */
    public static String calculateFileChecksum(String filePath) throws IOException {
        String content = readFileContent(filePath);
        return calculateMD5(content);
    }

    // === PERFORMANCE UTILITIES ===

    /**
     * Metot çalışma süresini ölçer
     */
    public static long measureExecutionTime(Runnable task) {
        long startTime = System.currentTimeMillis();
        task.run();
        return System.currentTimeMillis() - startTime;
    }

    /**
     * Memory kullanımını döndürür (MB)
     */
    public static long getMemoryUsage() {
        Runtime runtime = Runtime.getRuntime();
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
    }

    // === COLLECTION UTILITIES ===

    /**
     * Liste null-safe şekilde kontrol eder
     */
    public static boolean isNullOrEmpty(List<?> list) {
        return list == null || list.isEmpty();
    }

    /**
     * Map null-safe şekilde kontrol eder
     */
    public static boolean isNullOrEmpty(Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

    /**
     * Thread-safe şekilde liste kopyalar
     */
    public static <T> List<T> safeCopy(List<T> original) {
        if (original == null) return new ArrayList<>();

        synchronized (original) {
            return new ArrayList<>(original);
        }
    }
}