package Common;

import  Exception.MessageParseException;

// JSON işlemleri için basit manual parsing kullanacağız

/**
 * MTP (Multi-user Text Protocol) için mesaj sınıfı
 * Format: [TYPE]|[LENGTH]|[USER_ID]|[FILE_ID]|[DATA]|[TIMESTAMP]\n
 */
public class Message {

    // Enum tanımları
    public enum MessageType {
        // Bağlantı mesajları
        CONNECT, CONNECT_ACK, DISCONNECT,

        // Dosya işlemleri
        FILE_LIST, FILE_LIST_RESP, FILE_CREATE, FILE_OPEN, FILE_CONTENT,

        // Metin düzenleme
        TEXT_INSERT, TEXT_DELETE, TEXT_UPDATE,

        // İmleç işlemleri
        CURSOR_MOVE, CURSOR_UPDATE,

        // Kaydetme
        SAVE, SAVE_ACK,

        // Hata
        ERROR
    }

    // Sınıf özellikleri
    private MessageType type;
    private int length;
    private String userId;
    private String fileId;
    private String data;
    private long timestamp;

    // Manual JSON parsing için yardımcı metotlar
    private static final String DELIMITER = "|";
    private static final String MESSAGE_END = "\n";

    // Constructors
    public Message() {
        this.timestamp = System.currentTimeMillis();
    }

    public Message(MessageType type, String userId, String fileId, String data) {
        this.type = type;
        this.userId = userId;
        this.fileId = fileId;
        this.data = data != null ? data : "{}";
        this.length = this.data.length();
        this.timestamp = System.currentTimeMillis();
    }

    public Message(MessageType type, String userId, String fileId, Object dataObject) {
        this(type, userId, fileId, objectToJson(dataObject));
    }

    // Ana serialize metodu
    public String serialize() {
        return String.format("%s%s%d%s%s%s%s%s%s%s%d%s",
                type != null ? type.toString() : "NULL", DELIMITER,
                length, DELIMITER,
                userId != null ? userId : "null", DELIMITER,
                fileId != null ? fileId : "null", DELIMITER,
                data != null ? data : "{}", DELIMITER,
                timestamp, MESSAGE_END);
    }

    // Ana deserialize metodu
    public static Message deserialize(String rawMessage) throws MessageParseException {
        if (rawMessage == null || rawMessage.trim().isEmpty()) {
            throw new MessageParseException("Boş mesaj");
        }

        // Son \n karakterini temizle
        String cleanMessage = rawMessage.trim();
        if (cleanMessage.endsWith(MESSAGE_END)) {
            cleanMessage = cleanMessage.substring(0, cleanMessage.length() - 1);
        }

        // | karakteri ile ayır (maksimum 6 parça)
        String[] parts = cleanMessage.split("\\" + DELIMITER, 6);

        if (parts.length != 6) {
            throw new MessageParseException("Geçersiz mesaj formatı. Beklenen 6 parça, bulunan: " + parts.length);
        }

        try {
            Message message = new Message();

            // MessageType parse et
            message.type = MessageType.valueOf(parts[0]);

            // Length parse et
            message.length = Integer.parseInt(parts[1]);

            // UserID parse et (null kontrolü)
            message.userId = "null".equals(parts[2]) ? null : parts[2];

            // FileID parse et (null kontrolü)
            message.fileId = "null".equals(parts[3]) ? null : parts[3];

            // Data parse et
            message.data = parts[4];

            // Data length kontrolü
            if (message.data.length() != message.length) {
                throw new MessageParseException("Data uzunluğu uyuşmazlığı. Beklenen: " +
                        message.length + ", Gerçek: " + message.data.length());
            }

            // Timestamp parse et
            message.timestamp = Long.parseLong(parts[5]);

            return message;

        } catch (IllegalArgumentException e) {
            throw new MessageParseException("Mesaj parse hatası: " + e.getMessage());
        }
    }

    // Data'yı JSON object olarak al - basit parsing
    public String getDataValue(String key) {
        if (data == null || data.equals("{}")) {
            return null;
        }

        // Basit JSON parsing: {"key":"value"} formatı için
        String searchKey = "\"" + key + "\":\"";
        int startIndex = data.indexOf(searchKey);
        if (startIndex == -1) {
            return null;
        }

        startIndex += searchKey.length();
        int endIndex = data.indexOf("\"", startIndex);
        if (endIndex == -1) {
            return null;
        }

        return data.substring(startIndex, endIndex);
    }

    // Integer değer al
    public Integer getDataValueAsInt(String key) {
        String value = getDataValue(key);
        if (value == null) return null;

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Data'yı set et (object'ten) - manual JSON creation
    public void setDataFromObject(Object obj) {
        this.data = objectToJson(obj);
        this.length = this.data.length();
    }

    // Basit JSON serialization
    private static String objectToJson(Object obj) {
        if (obj == null) return "{}";

        if (obj instanceof ConnectData) {
            ConnectData cd = (ConnectData) obj;
            return String.format("{\"username\":\"%s\"}", cd.getUsername());
        } else if (obj instanceof ConnectAckData) {
            ConnectAckData cad = (ConnectAckData) obj;
            return String.format("{\"status\":\"%s\",\"userId\":\"%s\"}",
                    cad.getStatus(), cad.getUserId());
        } else if (obj instanceof TextOperationData) {
            TextOperationData tod = (TextOperationData) obj;
            return String.format("{\"position\":\"%d\",\"text\":\"%s\"}",
                    tod.getPosition(), escapeJson(tod.getText()));
        } else if (obj instanceof TextDeleteData) {
            TextDeleteData tdd = (TextDeleteData) obj;
            return String.format("{\"position\":\"%d\",\"length\":\"%d\"}",
                    tdd.getPosition(), tdd.getLength());
        } else if (obj instanceof ErrorData) {
            ErrorData ed = (ErrorData) obj;
            return String.format("{\"code\":\"%d\",\"message\":\"%s\"}",
                    ed.getCode(), escapeJson(ed.getMessage()));
        }

        return "{}";
    }

    // JSON string escape
    private static String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    // Validation metodu
    public boolean isValid() {
        return type != null &&
                data != null &&
                length >= 0 &&
                data.length() == length &&
                timestamp > 0;
    }

    // Factory metotları (yaygın mesajlar için)
    public static Message createConnectMessage(String username) {
        return new Message(MessageType.CONNECT, null, null,
                String.format("{\"username\":\"%s\"}", username));
    }

    public static Message createConnectAckMessage(String userId, boolean success) {
        return new Message(MessageType.CONNECT_ACK, userId, null,
                String.format("{\"status\":\"%s\",\"userId\":\"%s\"}",
                        success ? "success" : "fail", userId));
    }

    public static Message createTextInsertMessage(String userId, String fileId, int position, String text) {
        return new Message(MessageType.TEXT_INSERT, userId, fileId,
                String.format("{\"position\":\"%d\",\"text\":\"%s\"}",
                        position, escapeJson(text)));
    }

    public static Message createTextDeleteMessage(String userId, String fileId, int position, int length) {
        return new Message(MessageType.TEXT_DELETE, userId, fileId,
                String.format("{\"position\":\"%d\",\"length\":\"%d\"}",
                        position, length));
    }

    public static Message createErrorMessage(String userId, int errorCode, String errorMessage) {
        return new Message(MessageType.ERROR, userId, null,
                String.format("{\"code\":\"%d\",\"message\":\"%s\"}",
                        errorCode, escapeJson(errorMessage)));
    }

    // Getters ve Setters
    public MessageType getType() { return type; }
    public void setType(MessageType type) { this.type = type; }

    public int getLength() { return length; }
    public void setLength(int length) { this.length = length; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public String getData() { return data; }
    public void setData(String data) {
        this.data = data;
        this.length = data != null ? data.length() : 0;
    }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    // ToString metodu (debugging için)
    @Override
    public String toString() {
        return String.format("Message{type=%s, userId='%s', fileId='%s', data='%s', timestamp=%d}",
                type, userId, fileId, data, timestamp);
    }

    // Equals ve hashCode
    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        Message message = (Message) obj;
        return length == message.length &&
                timestamp == message.timestamp &&
                type == message.type &&
                java.util.Objects.equals(userId, message.userId) &&
                java.util.Objects.equals(fileId, message.fileId) &&
                java.util.Objects.equals(data, message.data);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(type, length, userId, fileId, data, timestamp);
    }
}

// Data sınıfları (JSON için)
class ConnectData {
    private String username;

    public ConnectData(String username) {
        this.username = username;
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
}

class ConnectAckData {
    private String status;
    private String userId;

    public ConnectAckData(String status, String userId) {
        this.status = status;
        this.userId = userId;
    }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
}

class TextOperationData {
    private int position;
    private String text;

    public TextOperationData(int position, String text) {
        this.position = position;
        this.text = text;
    }

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
}

class TextDeleteData {
    private int position;
    private int length;

    public TextDeleteData(int position, int length) {
        this.position = position;
        this.length = length;
    }

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public int getLength() { return length; }
    public void setLength(int length) { this.length = length; }
}

class ErrorData {
    private int code;
    private String message;

    public ErrorData(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() { return code; }
    public void setCode(int code) { this.code = code; }
    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}



