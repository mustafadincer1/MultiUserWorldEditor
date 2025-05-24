package Common;
public final class Protocol {
    private Protocol() {
        throw new AssertionError("Protocol sınıfı instantiate edilemez!");
    }

    // === NETWORK AYARLARI ===

    /** Varsayılan sunucu portu */
    public static final int DEFAULT_PORT = 8080;

    /** Alternatif portlar (test için) */
    public static final int TEST_PORT = 8081;
    public static final int DEBUG_PORT = 8082;

    /** Socket buffer boyutu */
    public static final int BUFFER_SIZE = 4096;

    /** Maksimum mesaj boyutu (4MB) */
    public static final int MAX_MESSAGE_SIZE = 4 * 1024 * 1024;

    /** Connection timeout (milisaniye) */
    public static final int CONNECTION_TIMEOUT = 30000; // 30 saniye

    /** Socket read timeout */
    public static final int SOCKET_READ_TIMEOUT = 5000; // 5 saniye

    /** Maksimum bağlantı sayısı */
    public static final int MAX_CONNECTIONS = 100;

    // === MESAJ FORMAT AYARLARI ===

    /** Mesaj field ayırıcısı */
    public static final String MESSAGE_DELIMITER = "|";

    /** Mesaj sonu karakteri */
    public static final String MESSAGE_END = "\n";

    /** Null değer string representasyonu */
    public static final String NULL_VALUE = "null";

    /** Boş data için varsayılan JSON */
    public static final String EMPTY_JSON = "{}";

    // === DOSYA AYARLARI ===

    /** Sunucuda dosyaların kaydedileceği klasör */
    public static final String DOCUMENTS_FOLDER = "documents/";

    /** Maksimum dosya ismi uzunluğu */
    public static final int MAX_FILENAME_LENGTH = 255;

    /** Maksimum dosya boyutu (10MB) */
    public static final long MAX_FILE_SIZE = 10 * 1024 * 1024;

    /** Desteklenen dosya uzantıları */
    public static final String[] SUPPORTED_EXTENSIONS = {".txt", ".rtf"};

    /** Varsayılan dosya uzantısı */
    public static final String DEFAULT_EXTENSION = ".txt";

    // === KULLANICI AYARLARI ===

    /** Maksimum kullanıcı adı uzunluğu */
    public static final int MAX_USERNAME_LENGTH = 50;

    /** Minimum kullanıcı adı uzunluğu */
    public static final int MIN_USERNAME_LENGTH = 3;

    /** User ID prefix */
    public static final String USER_ID_PREFIX = "user_";

    /** File ID prefix */
    public static final String FILE_ID_PREFIX = "file_";

    // === HATA KODLARI ===

    /** Genel hata */
    public static final int ERROR_GENERAL = 100;

    /** Geçersiz mesaj formatı */
    public static final int ERROR_INVALID_FORMAT = 101;

    /** Yetkisiz işlem */
    public static final int ERROR_UNAUTHORIZED = 102;

    /** Dosya bulunamadı */
    public static final int ERROR_FILE_NOT_FOUND = 103;

    /** Kullanıcı zaten bağlı */
    public static final int ERROR_USER_ALREADY_CONNECTED = 104;

    /** Dosya zaten açık */
    public static final int ERROR_FILE_ALREADY_OPEN = 105;

    /** Geçersiz kullanıcı adı */
    public static final int ERROR_INVALID_USERNAME = 106;

    /** Dosya boyutu çok büyük */
    public static final int ERROR_FILE_TOO_LARGE = 107;

    /** Maksimum bağlantı sayısı aşıldı */
    public static final int ERROR_MAX_CONNECTIONS = 108;

    /** Network hatası */
    public static final int ERROR_NETWORK = 109;

    /** Dosya yazma hatası */
    public static final int ERROR_FILE_WRITE = 110;

    // === HATA MESAJLARI ===

    /** Hata kodları için mesaj haritası */
    public static final java.util.Map<Integer, String> ERROR_MESSAGES =
            java.util.Collections.unmodifiableMap(new java.util.HashMap<Integer, String>() {{
                put(ERROR_GENERAL, "Genel hata oluştu");
                put(ERROR_INVALID_FORMAT, "Geçersiz mesaj formatı");
                put(ERROR_UNAUTHORIZED, "Bu işlem için yetkiniz yok");
                put(ERROR_FILE_NOT_FOUND, "Dosya bulunamadı");
                put(ERROR_USER_ALREADY_CONNECTED, "Kullanıcı zaten bağlı");
                put(ERROR_FILE_ALREADY_OPEN, "Dosya zaten açık");
                put(ERROR_INVALID_USERNAME, "Geçersiz kullanıcı adı");
                put(ERROR_FILE_TOO_LARGE, "Dosya boyutu çok büyük");
                put(ERROR_MAX_CONNECTIONS, "Maksimum bağlantı sayısına ulaşıldı");
                put(ERROR_NETWORK, "Ağ bağlantı hatası");
                put(ERROR_FILE_WRITE, "Dosya yazma hatası");
            }});

    // === OPERATIONAL TRANSFORM AYARLARI ===

    /** Text operation'lar için maksimum pozisyon */
    public static final int MAX_TEXT_POSITION = Integer.MAX_VALUE;

    /** Maksimum insert text uzunluğu */
    public static final int MAX_INSERT_LENGTH = 10000;

    /** Maksimum delete length */
    public static final int MAX_DELETE_LENGTH = 10000;

    // === TIMING AYARLARI ===

    /** Otomatik kaydetme aralığı (milisaniye) */
    public static final long AUTO_SAVE_INTERVAL = 10000; // 10 saniye

    /** Heartbeat aralığı */
    public static final long HEARTBEAT_INTERVAL = 30000; // 30 saniye

    /** Client timeout süresi */
    public static final int CLIENT_TIMEOUT = 60000; // 1 dakika

    /** Cursor update throttle (milisaniye) */
    public static final long CURSOR_UPDATE_THROTTLE = 100; // 100ms

    // === DEBUG VE LOG AYARLARI ===

    /** Debug mode flag */
    public static final boolean DEBUG_MODE = true;

    /** Verbose logging */
    public static final boolean VERBOSE_LOGGING = false;

    /** Log dosyası ismi */
    public static final String LOG_FILE = "mtp_server.log";

    // === YARDIMCI METOTLAR ===

    /**
     * Hata kodu için mesaj döndürür
     */
    public static String getErrorMessage(int errorCode) {
        return ERROR_MESSAGES.getOrDefault(errorCode, "Bilinmeyen hata");
    }

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
            System.out.println("DEBUG: Username null veya boş"); // DEBUG
            return false;
        }

        String trimmed = username.trim();
        boolean lengthOk = trimmed.length() >= MIN_USERNAME_LENGTH &&
                trimmed.length() <= MAX_USERNAME_LENGTH;
        boolean regexOk = trimmed.matches("^[a-zA-Z0-9_-]+$");

        System.out.println("DEBUG: Username='" + username + "' trimmed='" + trimmed + "'"); // DEBUG
        System.out.println("DEBUG: Length OK=" + lengthOk + " (len=" + trimmed.length() + ")"); // DEBUG
        System.out.println("DEBUG: Regex OK=" + regexOk); // DEBUG

        return lengthOk && regexOk;
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

        // Yasak karakterler
        String[] invalidChars = {"<", ">", ":", "\"", "|", "?", "*", "/", "\\"};
        for (String invalidChar : invalidChars) {
            if (trimmed.contains(invalidChar)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Desteklenen dosya uzantısı olup olmadığını kontrol eder
     */
    public static boolean isSupportedExtension(String filename) {
        if (filename == null) return false;

        for (String ext : SUPPORTED_EXTENSIONS) {
            if (filename.toLowerCase().endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Unique User ID üretir
     */
    public static String generateUserId() {
        return USER_ID_PREFIX + System.currentTimeMillis() + "_" +
                (int)(Math.random() * 1000);
    }

    /**
     * Unique File ID üretir
     */
    public static String generateFileId() {
        return FILE_ID_PREFIX + System.currentTimeMillis() + "_" +
                (int)(Math.random() * 1000);
    }

    /**
     * Mesaj boyutunun geçerli olup olmadığını kontrol eder
     */
    public static boolean isValidMessageSize(int size) {
        return size > 0 && size <= MAX_MESSAGE_SIZE;
    }

    /**
     * Text pozisyonunun geçerli olup olmadığını kontrol eder
     */
    public static boolean isValidTextPosition(int position) {
        return position >= 0 && position <= MAX_TEXT_POSITION;
    }

    /**
     * Insert text uzunluğunun geçerli olup olmadığını kontrol eder
     */
    public static boolean isValidInsertLength(int length) {
        return length > 0 && length <= MAX_INSERT_LENGTH;
    }

    /**
     * Delete length'in geçerli olup olmadığını kontrol eder
     */
    public static boolean isValidDeleteLength(int length) {
        return length > 0 && length <= MAX_DELETE_LENGTH;
    }

    // === VERSION BİLGİSİ ===

    /** Protokol versiyonu */
    public static final String PROTOCOL_VERSION = "1.0";

    /** Minimum desteklenen versiyon */
    public static final String MIN_SUPPORTED_VERSION = "1.0";

    /** Proje ismi */
    public static final String PROJECT_NAME = "MTP - Multi-user Text Editor";

    /** Build timestamp (compile time'da set edilecek) */
    public static final String BUILD_TIMESTAMP = java.time.Instant.now().toString();
}