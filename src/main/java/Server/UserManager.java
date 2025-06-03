package Server;

import Common.Protocol;
import Common.Utils;
// JSON library kullanmadan manuel parsing

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Kullanıcı kayıt ve giriş yönetimi
 * Basit text tabanlı kullanıcı veritabanı
 */
public class UserManager {

    private static final String USERS_FILE = "users.txt";

    // Memory'de kullanıcı veritabanı (username -> User)
    private final Map<String, User> users = new ConcurrentHashMap<>();

    // Aktif oturumlar (userId -> username)
    private final Map<String, String> activeSessions = new ConcurrentHashMap<>();

    /**
     * Constructor - kullanıcıları yükle
     */
    public UserManager() {
        loadUsers();
        Protocol.log("UserManager başlatıldı. Kayıtlı kullanıcı: " + users.size());
    }

    /**
     * Yeni kullanıcı kaydı
     */
    public RegisterResult registerUser(String username, String password) {
        // Validation
        if (!Protocol.isValidUsername(username)) {
            return new RegisterResult(false, "Geçersiz kullanıcı adı formatı");
        }

        if (password == null || password.trim().length() < 3) {
            return new RegisterResult(false, "Şifre en az 3 karakter olmalı");
        }

        if (password.trim().length() > 50) {
            return new RegisterResult(false, "Şifre en fazla 50 karakter olabilir");
        }

        // Kullanıcı zaten var mı?
        if (users.containsKey(username.toLowerCase())) {
            return new RegisterResult(false, "Bu kullanıcı adı zaten kullanılıyor");
        }

        try {
            // Yeni kullanıcı oluştur
            User newUser = new User(username, password.trim());
            users.put(username.toLowerCase(), newUser);

            // Text dosyasına kaydet
            saveUsers();

            Protocol.log("Yeni kullanıcı kaydedildi: " + username);
            return new RegisterResult(true, "Kayıt başarılı");

        } catch (Exception e) {
            Protocol.logError("Kullanıcı kaydetme hatası: " + username, e);
            return new RegisterResult(false, "Kayıt sırasında hata oluştu");
        }
    }

    /**
     * Kullanıcı girişi
     */
    public LoginResult loginUser(String username, String password) {
        // Validation
        if (username == null || password == null) {
            return new LoginResult(false, null, "Kullanıcı adı ve şifre gerekli");
        }

        User user = users.get(username.toLowerCase());
        if (user == null) {
            return new LoginResult(false, null, "Kullanıcı bulunamadı");
        }

        if (!user.password.equals(password.trim())) {
            return new LoginResult(false, null, "Hatalı şifre");
        }

        // Kullanıcı zaten giriş yapmış mı?
        if (isUserLoggedIn(username)) {
            return new LoginResult(false, null, "Bu kullanıcı zaten giriş yapmış");
        }

        // Başarılı giriş - session oluştur
        String userId = Protocol.generateUserId();
        activeSessions.put(userId, username);
        user.lastLoginTime = System.currentTimeMillis();

        // Text dosyasına kaydet (son giriş zamanı için)
        saveUsers();

        Protocol.log("Kullanıcı giriş yaptı: " + username + " (" + userId + ")");
        return new LoginResult(true, userId, "Giriş başarılı");
    }

    /**
     * Kullanıcı çıkışı
     */
    public void logoutUser(String userId) {
        String username = activeSessions.remove(userId);
        if (username != null) {
            Protocol.log("Kullanıcı çıkış yaptı: " + username + " (" + userId + ")");
        }
    }

    /**
     * UserId'den username al
     */
    public String getUsernameByUserId(String userId) {
        return activeSessions.get(userId);
    }

    /**
     * Username'den aktif userId al
     */
    public String getUserIdByUsername(String username) {
        for (Map.Entry<String, String> entry : activeSessions.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(username)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * Kullanıcı giriş yapmış mı?
     */
    public boolean isUserLoggedIn(String username) {
        return activeSessions.containsValue(username.toLowerCase());
    }

    /**
     * Session geçerli mi?
     */
    public boolean isValidSession(String userId) {
        return userId != null && activeSessions.containsKey(userId);
    }

    /**
     * Aktif kullanıcı sayısı
     */
    public int getActiveUserCount() {
        return activeSessions.size();
    }

    /**
     * Toplam kayıtlı kullanıcı sayısı
     */
    public int getTotalUserCount() {
        return users.size();
    }

    // === TEXT DOSYA İŞLEMLERİ ===

    /**
     * Kullanıcıları basit format'tan yükle
     * Format: username:password:registrationTime:lastLoginTime
     */
    private void loadUsers() {
        try {
            if (!Files.exists(Paths.get(USERS_FILE))) {
                Protocol.log("Kullanıcı dosyası bulunamadı, yeni dosya oluşturulacak");
                return;
            }

            String content = Utils.readFileContent(USERS_FILE);
            if (content.trim().isEmpty()) {
                return;
            }

            String[] lines = content.split("\n");

            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue; // Boş satır veya yorum
                }

                String[] parts = line.split(":");
                if (parts.length >= 2) {
                    String username = parts[0];
                    String password = parts[1];
                    long registrationTime = parts.length > 2 ?
                            Long.parseLong(parts[2]) : System.currentTimeMillis();
                    long lastLoginTime = parts.length > 3 ?
                            Long.parseLong(parts[3]) : 0;

                    User user = new User(username, password);
                    user.registrationTime = registrationTime;
                    user.lastLoginTime = lastLoginTime;

                    users.put(username.toLowerCase(), user);
                }
            }

            Protocol.log("Kullanıcılar yüklendi: " + users.size());

        } catch (Exception e) {
            Protocol.logError("Kullanıcı yükleme hatası", e);
        }
    }

    /**
     * Kullanıcıları basit format'a kaydet
     * Format: username:password:registrationTime:lastLoginTime
     */
    private void saveUsers() {
        try {
            StringBuilder content = new StringBuilder();
            content.append("# MTP User Database\n");
            content.append("# Format: username:password:registrationTime:lastLoginTime\n");
            content.append("# Auto-generated file\n\n");

            for (User user : users.values()) {
                content.append(user.username).append(":")
                        .append(user.password).append(":")
                        .append(user.registrationTime).append(":")
                        .append(user.lastLoginTime).append("\n");
            }

            Utils.writeFileContent(USERS_FILE, content.toString());

        } catch (IOException e) {
            Protocol.logError("Kullanıcı kaydetme hatası", e);
        }
    }

    /**
     * Tüm aktif sessionları temizle (server restart için)
     */
    public void clearAllSessions() {
        activeSessions.clear();
        Protocol.log("Tüm aktif sessionlar temizlendi");
    }

    // === INNER CLASSES ===

    /**
     * Kullanıcı bilgileri
     */
    private static class User {
        public final String username;
        public final String password;
        public long registrationTime;
        public long lastLoginTime;

        public User(String username, String password) {
            this.username = username;
            this.password = password;
            this.registrationTime = System.currentTimeMillis();
            this.lastLoginTime = 0;
        }
    }

    /**
     * Kayıt sonucu
     */
    public static class RegisterResult {
        public final boolean success;
        public final String message;

        public RegisterResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }
    }

    /**
     * Giriş sonucu
     */
    public static class LoginResult {
        public final boolean success;
        public final String userId;
        public final String message;

        public LoginResult(boolean success, String userId, String message) {
            this.success = success;
            this.userId = userId;
            this.message = message;
        }
    }
}