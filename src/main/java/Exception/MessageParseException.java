package Exception;

/**
 * Mesaj parse işlemlerinde oluşan hatalar için özel exception sınıfı
 * Protokol mesajlarının serialize/deserialize işlemlerinde kullanılır
 */
public class MessageParseException extends Exception {

    /**
     * Constructor - sadece mesaj ile
     */
    public MessageParseException(String message) {
        super(message);
    }

    /**
     * Constructor - mesaj ve cause ile
     */
    public MessageParseException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructor - sadece cause ile
     */
    public MessageParseException(Throwable cause) {
        super(cause);
    }
}