package Common;

import java.util.HashMap;
import java.util.Map;

/**
 * MTP (Multi-user Text Protocol) Mesaj Sınıfı - Sadeleştirilmiş Versiyon
 * Format: TYPE|USER_ID|FILE_ID|DATA_FIELD1:VALUE1,FIELD2:VALUE2|TIMESTAMP\n
 */
public class Message {

    // Mesaj tipleri - sadece gerekli olanlar
    public enum MessageType {
        // Bağlantı
        CONNECT, CONNECT_ACK, DISCONNECT,
        // Dosya işlemleri
        FILE_LIST, FILE_LIST_RESP, FILE_CREATE, FILE_OPEN, FILE_CONTENT,
        // Metin düzenleme
        TEXT_INSERT, TEXT_DELETE, TEXT_UPDATE,

        FILE_DELETE, FILE_DELETE_ACK,
        // Diğer
        SAVE, ERROR,

        REGISTER, REGISTER_ACK, LOGIN, LOGIN_ACK
    }

    // Mesaj alanları
    private MessageType type;
    private String userId;
    private String fileId;
    private Map<String, String> data;
    private long timestamp;

    // Sabitler
    private static final String DELIMITER = "|";
    private static final String MESSAGE_END = "\n";
    private static final String DATA_SEPARATOR = ",";
    private static final String KEY_VALUE_SEPARATOR = ":";

    // Constructors
    public Message() {
        this.data = new HashMap<>();
        this.timestamp = System.currentTimeMillis();
    }

    public Message(MessageType type, String userId, String fileId) {
        this();
        this.type = type;
        this.userId = userId;
        this.fileId = fileId;
    }

    // Data ekleme metotları
    public Message addData(String key, String value) {
        if (key != null && value != null) {
            data.put(key, value);
        }
        return this;
    }
    public Map<String, String> getAllData() {
        return data != null ? new HashMap<>(data) : new HashMap<>();
    }
    public static Message createFileDelete(String userId, String fileId) {
        return new Message(MessageType.FILE_DELETE, userId, fileId);
    }

    /**
     * FILE_DELETE_ACK mesajı oluştur
     */
    public static Message createFileDeleteAck(String userId, String fileId, boolean success, String message) {
        return new Message(MessageType.FILE_DELETE_ACK, userId, fileId)
                .addData("status", success ? "success" : "fail")
                .addData("message", message);
    }

    public Message addData(String key, int value) {
        return addData(key, String.valueOf(value));
    }

    // Data alma metotları
    public String getData(String key) {
        return data.get(key);
    }

    public Integer getDataAsInt(String key) {
        String value = getData(key);
        if (value == null) return null;

        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // Serialize - mesajı string'e çevir
    public String serialize() {
        StringBuilder sb = new StringBuilder();

        // TYPE|USER_ID|FILE_ID|DATA|TIMESTAMP\n
        sb.append(type != null ? type.name() : "NULL").append(DELIMITER);
        sb.append(userId != null ? userId : "null").append(DELIMITER);
        sb.append(fileId != null ? fileId : "null").append(DELIMITER);
        sb.append(serializeData()).append(DELIMITER);
        sb.append(timestamp).append(MESSAGE_END);

        return sb.toString();
    }

    // Data'yı serialize et: key1:value1,key2:value2
    private String serializeData() {
        if (data.isEmpty()) {
            return "empty";
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;

        for (Map.Entry<String, String> entry : data.entrySet()) {
            if (!first) sb.append(DATA_SEPARATOR);
            sb.append(entry.getKey()).append(KEY_VALUE_SEPARATOR).append(entry.getValue());
            first = false;
        }

        return sb.toString();
    }

    // Deserialize - string'den mesaj oluştur
    public static Message deserialize(String rawMessage) throws Exception {
        if (rawMessage == null || rawMessage.trim().isEmpty()) {
            throw new Exception("Boş mesaj");
        }

        // \n'i temizle
        String cleanMessage = rawMessage.trim();
        if (cleanMessage.endsWith(MESSAGE_END)) {
            cleanMessage = cleanMessage.substring(0, cleanMessage.length() - 1);
        }

        // | ile ayır
        String[] parts = cleanMessage.split("\\" + DELIMITER, 5);
        if (parts.length != 5) {
            throw new Exception("Geçersiz mesaj formatı: " + parts.length + " parça");
        }

        Message message = new Message();

        // MessageType
        try {
            message.type = MessageType.valueOf(parts[0]);
        } catch (IllegalArgumentException e) {
            throw new Exception("Geçersiz mesaj tipi: " + parts[0]);
        }

        // User ID
        message.userId = "null".equals(parts[1]) ? null : parts[1];

        // File ID
        message.fileId = "null".equals(parts[2]) ? null : parts[2];

        // Data
        message.parseData(parts[3]);

        // Timestamp
        try {
            message.timestamp = Long.parseLong(parts[4]);
        } catch (NumberFormatException e) {
            message.timestamp = System.currentTimeMillis();
        }

        return message;
    }

    // Data string'ini parse et
    private void parseData(String dataString) {
        if ("empty".equals(dataString) || dataString.trim().isEmpty()) {
            return;
        }

        String[] pairs = dataString.split(DATA_SEPARATOR);

        for (String pair : pairs) {
            String[] keyValue = pair.split(KEY_VALUE_SEPARATOR, 2);
            if (keyValue.length == 2) {
                data.put(keyValue[0].trim(), keyValue[1].trim());
            }
        }
    }

    // Factory metotları - yaygın mesajlar için
    public static Message createConnect(String username) {
        return new Message(MessageType.CONNECT, null, null)
                .addData("username", username);
    }

    public static Message createConnectAck(String userId, boolean success) {
        return new Message(MessageType.CONNECT_ACK, userId, null)
                .addData("status", success ? "success" : "fail");
    }

    public static Message createFileList() {
        return new Message(MessageType.FILE_LIST, null, null);
    }

    public static Message createFileCreate(String userId, String fileName) {
        return new Message(MessageType.FILE_CREATE, userId, null)
                .addData("name", fileName);
    }

    public static Message createFileOpen(String userId, String fileId) {
        return new Message(MessageType.FILE_OPEN, userId, fileId);
    }

    public static Message createFileContent(String userId, String fileId, String content) {
        return new Message(MessageType.FILE_CONTENT, userId, fileId)
                .addData("content", content);
    }

    public static Message createTextInsert(String userId, String fileId, int position, String text) {
        return new Message(MessageType.TEXT_INSERT, userId, fileId)
                .addData("position", position)
                .addData("text", text);
    }

    public static Message createTextDelete(String userId, String fileId, int position, int length) {
        return new Message(MessageType.TEXT_DELETE, userId, fileId)
                .addData("position", position)
                .addData("length", length);
    }

    public static Message createTextUpdate(String userId, String fileId, String operation, int position, String text) {
        return new Message(MessageType.TEXT_UPDATE, userId, fileId)
                .addData("operation", operation)
                .addData("position", position)
                .addData("text", text);
    }

    public static Message createSave(String userId, String fileId) {
        return new Message(MessageType.SAVE, userId, fileId);
    }

    public static Message createError(String userId, String errorMessage) {
        return new Message(MessageType.ERROR, userId, null)
                .addData("message", errorMessage);
    }
    // Message.java'ya eklenecek factory metotları
    public static Message createRegister(String username, String password) {
        return new Message(MessageType.REGISTER, null, null)
                .addData("username", username)
                .addData("password", password);
    }

    public static Message createRegisterAck(boolean success, String message) {
        return new Message(MessageType.REGISTER_ACK, null, null)
                .addData("status", success ? "success" : "fail")
                .addData("message", message);
    }

    public static Message createLogin(String username, String password) {
        return new Message(MessageType.LOGIN, null, null)
                .addData("username", username)
                .addData("password", password);
    }

    public static Message createLoginAck(String userId, boolean success, String message) {
        return new Message(MessageType.LOGIN_ACK, userId, null)
                .addData("status", success ? "success" : "fail")
                .addData("message", message);
    }

    public static Message createDisconnect(String userId) {
        return new Message(MessageType.DISCONNECT, userId, null);
    }

    // Validation
    public boolean isValid() {
        return type != null && timestamp > 0;
    }

    // Getters & Setters
    public MessageType getType() { return type; }
    public void setType(MessageType type) { this.type = type; }

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    // Debug
    @Override
    public String toString() {
        return String.format("Message{type=%s, userId='%s', fileId='%s', data=%s, timestamp=%d}",
                type, userId, fileId, data, timestamp);
    }
}