package Common;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

/**
 * MTP projesi için minimal utility metotları
 * Sadece gerçekten gerekli olan network, file ve logging işlemleri
 * UPDATED: fileName - fileId.txt format desteği eklendi
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
        try {
            PrintWriter writer = new PrintWriter(
                    new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);

            // 🔧 NEWLINE DEBUG
            if (message.contains("\n")) {
                long newlineCount = message.chars().filter(ch -> ch == '\n').count();
                System.out.println("DEBUG: writeToSocket - Sending message with newlines:");
                System.out.println("DEBUG: Message length: " + message.length());
                System.out.println("DEBUG: Newline count: " + newlineCount);
                System.out.println("DEBUG: Message preview: '" +
                        (message.length() > 100 ? message.substring(0, 100) + "..." : message) + "'");
            }

            writer.print(message);
            writer.flush();

            if (message.contains("\n")) {
                System.out.println("DEBUG: writeToSocket - Message sent successfully to " +
                        socket.getRemoteSocketAddress());
            }

        } catch (IOException e) {
            System.err.println("ERROR: writeToSocket failed: " + e.getMessage());
            throw e;
        }
    }

    // === FILE UTILITIES ===

    /**
     * 🔧 UPDATED: Dosya adını temizle - fileId için özel kontrol
     */
    public static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.trim().isEmpty()) {
            return null;
        }

        // Boşlukları temizle
        String cleaned = fileName.trim();

        // 🔧 FileId pattern kontrolü - fileId'ler olduğu gibi bırakılır
        if (cleaned.matches("^file_\\d+_\\d+$")) {
            // Bu bir fileId, sanitize etme
            return cleaned;
        }

        // Normal dosya adları için sanitize
        cleaned = cleaned.replaceAll("[<>:\"|?*\\\\]", "");
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
     * 🔧 NEW: FileId pattern ile eşleşen dosyayı ara
     * documents/ klasöründe fileName - fileId.txt pattern'ine uyan dosyayı bulur
     */
    public static String findFileByFileId(String documentsPath, String fileId) {
        try {
            System.out.println("DEBUG: findFileByFileId - documentsPath: " + documentsPath + ", fileId: " + fileId);

            Path documentsDir = Paths.get(documentsPath);
            if (!Files.exists(documentsDir)) {
                System.out.println("DEBUG: Documents klasörü bulunamadı: " + documentsPath);
                return null;
            }

            // fileId ile biten dosyaları ara
            String targetPattern = " - " + fileId + ".txt";
            System.out.println("DEBUG: Aranan pattern: *" + targetPattern);

            Path matchingFile = Files.list(documentsDir)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(targetPattern))
                    .findFirst()
                    .orElse(null);

            if (matchingFile != null) {
                String foundPath = matchingFile.toString();
                System.out.println("DEBUG: Eşleşen dosya bulundu: " + foundPath);
                return foundPath;
            } else {
                System.out.println("DEBUG: Pattern ile eşleşen dosya bulunamadı");
                return null;
            }

        } catch (Exception e) {
            System.out.println("DEBUG: findFileByFileId exception: " + e.getMessage());
            return null;
        }
    }

    /**
     * 🔧 NEW: Disk dosya adından fileName çıkar
     * "My Document - file_123456.txt" -> "My Document"
     */
    public static String extractFileNameFromDiskName(String diskFileName) {
        try {
            if (diskFileName == null || !diskFileName.endsWith(".txt")) {
                return null;
            }

            // .txt uzantısını kaldır
            String nameWithoutExt = diskFileName.replace(".txt", "");

            // " - file_" pattern'ini ara
            int lastDashIndex = nameWithoutExt.lastIndexOf(" - file_");
            if (lastDashIndex == -1) {
                return null;
            }

            // FileName kısmını al
            String fileName = nameWithoutExt.substring(0, lastDashIndex);
            return fileName.trim();

        } catch (Exception e) {
            System.out.println("DEBUG: extractFileNameFromDiskName exception: " + e.getMessage());
            return null;
        }
    }

    /**
     * 🔧 NEW: Disk dosya adından fileId çıkar
     * "My Document - file_123456.txt" -> "file_123456"
     */
    public static String extractFileIdFromDiskName(String diskFileName) {
        try {
            if (diskFileName == null || !diskFileName.endsWith(".txt")) {
                return null;
            }

            // .txt uzantısını kaldır
            String nameWithoutExt = diskFileName.replace(".txt", "");

            // " - file_" pattern'ini ara
            int lastDashIndex = nameWithoutExt.lastIndexOf(" - file_");
            if (lastDashIndex == -1) {
                return null;
            }

            // FileId kısmını al
            String fileId = nameWithoutExt.substring(lastDashIndex + 3); // " - " = 3 karakter
            return fileId.trim();

        } catch (Exception e) {
            System.out.println("DEBUG: extractFileIdFromDiskName exception: " + e.getMessage());
            return null;
        }
    }

    /**
     * 🔧 NEW: FileName ve fileId'den disk dosya adı oluştur
     * "My Document", "file_123456" -> "My Document - file_123456.txt"
     */
    public static String createDiskFileName(String fileName, String fileId) {
        if (fileName == null || fileId == null) {
            return null;
        }

        // Dosya adını temizle
        String cleanFileName = sanitizeFileName(fileName);
        if (cleanFileName == null) {
            cleanFileName = "Untitled";
        }

        return cleanFileName + " - " + fileId + ".txt";
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
     * Socket'e güvenli şekilde mesaj yazar - newline escape ile
     */
    public static void writeToSocketSafe(Socket socket, String message) throws IOException {
        if (socket == null || socket.isClosed()) {
            throw new IOException("Socket kapalı veya null");
        }

        if (message == null) {
            throw new IllegalArgumentException("Mesaj null olamaz");
        }

        PrintWriter writer = new PrintWriter(
                new OutputStreamWriter(socket.getOutputStream(), "UTF-8"), true);

        // 🔧 Newline karakterlerini escape et (content içinde)
        String safeMessage = escapeNewlinesInContent(message);

        // Mesajın sonunda \n yoksa ekle
        if (!safeMessage.endsWith("\n")) {
            safeMessage += "\n";
        }

        System.out.println("DEBUG: Sending safe message: " + safeMessage);
        writer.print(safeMessage);
        writer.flush();
    }

    /**
     * Mesaj içeriğindeki newline karakterlerini escape eder
     */
    private static String escapeNewlinesInContent(String message) {
        if (message == null) return null;

        // MTP mesaj formatını parse et
        String[] parts = message.split("\\|", 5);
        if (parts.length < 4) {
            return message; // Format geçersizse olduğu gibi döndür
        }

        // DATA kısmındaki content alanını bul ve escape et
        String dataSection = parts[3];
        if (dataSection.contains("content:")) {
            // Pattern ve Matcher kullanarak content değerini bul
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("content:([^,]*)");
            java.util.regex.Matcher matcher = pattern.matcher(dataSection);

            StringBuffer sb = new StringBuffer();
            while (matcher.find()) {
                String contentValue = matcher.group(1);
                String escapedContent = contentValue
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("\t", "\\t");
                matcher.appendReplacement(sb, "content:" + java.util.regex.Matcher.quoteReplacement(escapedContent));
            }
            matcher.appendTail(sb);

            // Mesajı yeniden oluştur
            parts[3] = sb.toString();
            return String.join("|", parts);
        }

        return message;
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

    /**
     * 🔧 NEW: Güvenli dosya oluşturma - fileName - fileId.txt formatında
     */
    public static boolean createFileWithNewFormat(String documentsPath, String fileName, String fileId, String content) {
        try {
            System.out.println("DEBUG: createFileWithNewFormat - fileName: " + fileName + ", fileId: " + fileId);

            String diskFileName = createDiskFileName(fileName, fileId);
            if (diskFileName == null) {
                System.out.println("ERROR: Could not create disk file name");
                return false;
            }

            String fullPath = documentsPath + diskFileName;
            System.out.println("DEBUG: Full path: " + fullPath);

            return writeFileContent(fullPath, content);

        } catch (Exception e) {
            System.out.println("DEBUG: createFileWithNewFormat exception: " + e.getMessage());
            return false;
        }
    }

    /**
     * 🔧 NEW: Dosya adını güncelle - eski dosyayı sil, yeni isimle kaydet
     */
    public static boolean renameFileWithNewFormat(String documentsPath, String oldFileName, String newFileName, String fileId) {
        try {
            System.out.println("DEBUG: renameFileWithNewFormat - old: " + oldFileName + ", new: " + newFileName + ", fileId: " + fileId);

            // Eski dosyayı bul
            String oldDiskFileName = createDiskFileName(oldFileName, fileId);
            String oldPath = documentsPath + oldDiskFileName;

            // Yeni dosya adını oluştur
            String newDiskFileName = createDiskFileName(newFileName, fileId);
            String newPath = documentsPath + newDiskFileName;

            System.out.println("DEBUG: Old path: " + oldPath);
            System.out.println("DEBUG: New path: " + newPath);

            // Eski dosya var mı kontrol et
            if (!fileExists(oldPath)) {
                System.out.println("ERROR: Old file does not exist: " + oldPath);
                return false;
            }

            // İçeriği oku
            String content = readFileContent(oldPath);
            if (content == null) {
                System.out.println("ERROR: Could not read old file content");
                return false;
            }

            // Yeni dosyayı oluştur
            boolean created = writeFileContent(newPath, content);
            if (!created) {
                System.out.println("ERROR: Could not create new file");
                return false;
            }

            // Eski dosyayı sil
            try {
                Files.delete(Paths.get(oldPath));
                System.out.println("DEBUG: Old file deleted successfully");
            } catch (IOException e) {
                System.out.println("WARNING: Could not delete old file: " + e.getMessage());
                // Yeni dosya oluşturuldu, eski dosya silinemedi - hala başarılı
            }

            return true;

        } catch (Exception e) {
            System.out.println("DEBUG: renameFileWithNewFormat exception: " + e.getMessage());
            return false;
        }
    }

    /**
     * 🔧 NEW: Documents klasöründeki tüm yeni format dosyalarını listele
     */
    public static java.util.List<FileInfo> listAllDocuments(String documentsPath) {
        java.util.List<FileInfo> result = new java.util.ArrayList<>();

        try {
            System.out.println("DEBUG: listAllDocuments - path: " + documentsPath);

            Path documentsDir = Paths.get(documentsPath);
            if (!Files.exists(documentsDir)) {
                System.out.println("DEBUG: Documents directory does not exist");
                return result;
            }

            Files.list(documentsDir)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".txt"))
                    .filter(path -> path.getFileName().toString().contains(" - file_")) // New format pattern
                    .forEach(path -> {
                        try {
                            String diskFileName = path.getFileName().toString();
                            String fileId = extractFileIdFromDiskName(diskFileName);
                            String fileName = extractFileNameFromDiskName(diskFileName);

                            if (fileId != null && fileName != null) {
                                long lastModified = Files.getLastModifiedTime(path).toMillis();
                                long fileSize = Files.size(path);

                                FileInfo info = new FileInfo(fileId, fileName, diskFileName,
                                        path.toString(), lastModified, fileSize);
                                result.add(info);

                                System.out.println("DEBUG: Added to list - " + fileId + " -> " + fileName);
                            }

                        } catch (IOException e) {
                            System.out.println("DEBUG: Error processing file: " + path + " - " + e.getMessage());
                        }
                    });

        } catch (IOException e) {
            System.out.println("DEBUG: listAllDocuments exception: " + e.getMessage());
        }

        // Last modified tarihine göre sırala (en yeni önce)
        result.sort((a, b) -> Long.compare(b.lastModified, a.lastModified));

        System.out.println("DEBUG: Total files found: " + result.size());
        return result;
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

    // === INNER CLASSES ===

    /**
     * 🔧 NEW: Dosya bilgileri için data class
     */
    public static class FileInfo {
        public final String fileId;
        public final String fileName;
        public final String diskFileName;
        public final String fullPath;
        public final long lastModified;
        public final long fileSize;

        public FileInfo(String fileId, String fileName, String diskFileName,
                        String fullPath, long lastModified, long fileSize) {
            this.fileId = fileId;
            this.fileName = fileName;
            this.diskFileName = diskFileName;
            this.fullPath = fullPath;
            this.lastModified = lastModified;
            this.fileSize = fileSize;
        }

        @Override
        public String toString() {
            return String.format("FileInfo{fileId='%s', fileName='%s', diskFileName='%s', size=%d}",
                    fileId, fileName, diskFileName, fileSize);
        }
    }
}