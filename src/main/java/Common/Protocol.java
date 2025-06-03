package Common;

import java.util.concurrent.atomic.AtomicLong;

/**
 * MTP (Multi-user Text Protocol) Protokol Sabitleri - Sadeleştirilmiş Versiyon
 * Sadece gerekli sabitler ve temel utility metotları
 */
public final class Protocol {

    // Constructor'ı private yap
    private Protocol() {
        throw new AssertionError("Protocol sınıfı instantiate edilemez!");
    }

    // === TEMEL NETWORK AYARLARI ===
    public static final int DEFAULT_PORT = 8080;
    public static final int MAX_CONNECTIONS = 50;
    public static final int BUFFER_SIZE = 4096;
    public static final int CONNECTION_TIMEOUT = 30000; // 30 saniye

    // === DOSYA AYARLARI ===
    public static final String DOCUMENTS_FOLDER = "documents/";
    public static final String FILE_EXTENSION = ".txt";
    public static final int MAX_FILENAME_LENGTH = 100;
    public static final long MAX_FILE_SIZE = 5 * 1024 * 1024; // 5MB

    // === KULLANICI AYARLARI ===
    public static final int MAX_USERNAME_LENGTH = 30;
    public static final int MIN_USERNAME_LENGTH = 3;
    public static final String USER_ID_PREFIX = "user_";
    public static final String FILE_ID_PREFIX = "file_";

    // === TEMEL HATA KODLARI ===
    public static final String ERROR_INVALID_MESSAGE = "Geçersiz mesaj formatı";
    public static final String ERROR_INVALID_USERNAME = "Geçersiz kullanıcı adı";
    public static final String ERROR_USER_EXISTS = "Kullanıcı zaten bağlı";
    public static final String ERROR_FILE_NOT_FOUND = "Dosya bulunamadı";
    public static final String ERROR_UNAUTHORIZED = "Bu işlem için yetkiniz yok";
    public static final String ERROR_MAX_CONNECTIONS = "Maksimum bağlantı sayısına ulaşıldı";
    public static final String ERROR_GENERAL = "Genel hata oluştu";

    // === ID GENERATION ===
    private static final AtomicLong idCounter = new AtomicLong(1);

    /**
     * Unique User ID üretir
     */
    public static String generateUserId() {
        return USER_ID_PREFIX + System.currentTimeMillis() + "_" + idCounter.getAndIncrement();
    }

    /**
     * Unique File ID üretir
     */
    public static String generateFileId() {
        return FILE_ID_PREFIX + System.currentTimeMillis() + "_" + idCounter.getAndIncrement();
    }

    // === VALIDATION METOTLARI ===

    /**
     * Port numarasının geçerli olup olmadığını kontrol eder
     */
    public static boolean isValidPort(int port) {
        return port > 0 && port <= 65535;
    }

    /**
     * Kullanıcı adının geçerli olup olmadığını kontrol eder
     */
    public static boolean isValidUsername(String username) {
        if (username == null || username.trim().isEmpty()) {
            return false;
        }

        String trimmed = username.trim();
        return trimmed.length() >= MIN_USERNAME_LENGTH &&
                trimmed.length() <= MAX_USERNAME_LENGTH &&
                trimmed.matches("^[a-zA-Z0-9_-]+$"); // Sadece harf, rakam, _, -
    }

    /**
     * Dosya isminin geçerli olup olmadığını kontrol eder
     */
    public static boolean isValidFilename(String filename) {
        if (filename == null || filename.trim().isEmpty()) {
            return false;
        }

        String trimmed = filename.trim();
        if (trimmed.length() > MAX_FILENAME_LENGTH) {
            return false;
        }

        // Yasak karakterleri kontrol et
        String[] invalidChars = {"<", ">", ":", "\"", "|", "?", "*", "/", "\\"};
        for (String invalidChar : invalidChars) {
            if (trimmed.contains(invalidChar)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Text pozisyonunun geçerli olup olmadığını kontrol eder
     */
    public static boolean isValidPosition(String text, int position) {
        if (text == null) return position == 0;
        return position >= 0 && position <= text.length();
    }

    /**
     * Delete operasyonunun geçerli olup olmadığını kontrol eder
     */
    public static boolean isValidDelete(String text, int position, int length) {
        if (text == null || position < 0 || length <= 0) {
            return false;
        }
        return position + length <= text.length();
    }

    // === UTILITY METOTLARI ===

    /**
     * Console'a log yazar
     */
    public static void log(String message) {
        String timestamp = java.time.LocalDateTime.now().toString();
        System.out.println("[" + timestamp + "] " + message);
    }

    /**
     * Hata log'u yazar
     */
    public static void logError(String message, Throwable throwable) {
        String timestamp = java.time.LocalDateTime.now().toString();
        System.err.println("[" + timestamp + "] ERROR: " + message);
        if (throwable != null) {
            throwable.printStackTrace();
        }
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

    // === PROJE BİLGİLERİ ===
    public static final String PROJECT_NAME = "MTP - Multi-user Text Editor";
    public static final String VERSION = "1.0";
}