package Server;

import Common.OperationalTransform;
import Common.Protocol;
import Common.Utils;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Robust Operational Transform entegreli Document Manager
 * Çok kullanıcılı eş zamanlı düzenleme, 3+ kullanıcı conflict resolution
 */
public class DocumentManager {

    // Ana document storage
    private final Map<String, Document> documents = new ConcurrentHashMap<>();

    // Dosya kullanıcı mapping
    private final Map<String, Set<String>> fileUsers = new ConcurrentHashMap<>();

    // Auto-save için executor (basit versiyon)
    private final ScheduledExecutorService autoSaveExecutor;

    /**
     * Constructor
     */
    public DocumentManager() {
        // Documents klasörü oluştur
        createDocumentsDirectory();

        // Auto-save her 30 saniyede bir
        this.autoSaveExecutor = Executors.newSingleThreadScheduledExecutor();
        this.autoSaveExecutor.scheduleAtFixedRate(this::autoSaveAll, 30, 30, TimeUnit.SECONDS);

        Protocol.log("DocumentManager başlatıldı (Robust OT ile)");
    }

    /**
     * Documents klasörünü oluştur
     */
    private void createDocumentsDirectory() {
        try {
            Path documentsPath = Paths.get(Protocol.DOCUMENTS_FOLDER);
            if (!Files.exists(documentsPath)) {
                Files.createDirectories(documentsPath);
                Protocol.log("Documents klasörü oluşturuldu: " + Protocol.DOCUMENTS_FOLDER);
            }
        } catch (IOException e) {
            Protocol.logError("Documents klasörü oluşturulamadı", e);
        }
    }

    // === DOCUMENT OPERATIONS ===

    /**
     * Yeni doküman oluştur
     */
    public String createDocument(String fileName, String creatorUserId) {
        if (!Protocol.isValidFilename(fileName)) {
            Protocol.log("Geçersiz dosya ismi: " + fileName);
            return null;
        }

        try {
            // Unique file ID
            String fileId = Protocol.generateFileId();

            // Dosya uzantısı kontrolü
            if (!fileName.endsWith(Protocol.FILE_EXTENSION)) {
                fileName += Protocol.FILE_EXTENSION;
            }

            // Yeni document
            Document document = new Document(fileId, fileName, creatorUserId);
            documents.put(fileId, document);

            // Kullanıcıyı ekle
            addUserToFile(fileId, creatorUserId);

            Protocol.log("Doküman oluşturuldu: " + fileName + " (" + fileId + ") by " + creatorUserId);
            return fileId;

        } catch (Exception e) {
            Protocol.logError("Doküman oluşturma hatası: " + fileName, e);
            return null;
        }
    }

    /**
     * Dokümanı aç
     */
    public Document openDocument(String fileId, String userId) {
        Protocol.log("DEBUG: openDocument çağrıldı - fileId: '" + fileId + "', userId: '" + userId + "'");

        if (fileId == null || userId == null) {
            Protocol.log("ERROR: fileId veya userId null");
            return null;
        }

        // Memory'de var mı kontrol et
        Document doc = documents.get(fileId);
        Protocol.log("DEBUG: Memory'de bulundu mu: " + (doc != null));

        // Memory'de yoksa diskten yükle
        if (doc == null) {
            Protocol.log("DEBUG: Memory'de yok, diskten yükleniyor...");
            doc = loadDocumentFromDisk(fileId);
            if (doc != null) {
                documents.put(fileId, doc);
                Protocol.log("DEBUG: Diskten yüklendi ve memory'e kaydedildi");
            } else {
                Protocol.log("ERROR: Diskten yüklenemedi");
                return null;
            }
        }

        if (doc != null) {
            // Kullanıcıyı dosyaya ekle
            addUserToFile(fileId, userId);
            Protocol.log("DEBUG: Kullanıcı dosyaya eklendi - fileId: " + fileId + ", userId: " + userId);

            // Thread-safe copy döndür
            Document copy = doc.copy();
            Protocol.log("SUCCESS: Doküman başarıyla açıldı: " + fileId + " by " + userId);
            return copy;
        }

        Protocol.log("ERROR: Doküman açılamadı: " + fileId);
        return null;
    }



    // DocumentManager.java'ya bu metodları ekleyin

    /**
     * PUBLIC loadDocument metodu - ClientHandler için
     */
    public Document loadDocument(String fileId) {
        Protocol.log("DEBUG: loadDocument çağrıldı - fileId: " + fileId);

        if (fileId == null || fileId.trim().isEmpty()) {
            Protocol.log("ERROR: fileId null veya boş");
            return null;
        }

        // Memory'de var mı kontrol et
        if (documents.containsKey(fileId)) {
            Document doc = documents.get(fileId);
            Protocol.log("DEBUG: Dosya memory'den bulundu: " + fileId);
            return doc;
        }

        // Disk'ten yükle
        Protocol.log("DEBUG: Dosya memory'de yok, disk'ten yüklenecek: " + fileId);
        Document doc = loadDocumentFromDisk(fileId);

        if (doc != null) {
            documents.put(fileId, doc);
            Protocol.log("DEBUG: Dosya memory'e kaydedildi: " + fileId);
        }

        return doc;
    }

    /**
     * Kullanıcıyı dosyaya ekle - ClientHandler için
     */
    public void addUserToDocument(String fileId, String userId) {
        Protocol.log("DEBUG: addUserToDocument - fileId: " + fileId + ", userId: " + userId);
        addUserToFile(fileId, userId);
    }

    /**
     * Kullanıcıyı dosyadan çıkar - ClientHandler için
     */
    public void removeUserFromDocument(String fileId, String userId) {
        Protocol.log("DEBUG: removeUserFromDocument - fileId: " + fileId + ", userId: " + userId);
        removeUserFromFile(fileId, userId);
    }

    /**
     * loadDocumentFromDisk metodunu debug ile güncelleyin
     */
    private Document loadDocumentFromDisk(String fileId) {
        Protocol.log("=== LOAD DOCUMENT FROM DISK DEBUG ===");
        Protocol.log("DEBUG: fileId: '" + fileId + "'");

        try {
            // FileID'yi temizle
            String cleanFileId = Utils.sanitizeFileName(fileId);
            if (cleanFileId == null || cleanFileId.isEmpty()) {
                Protocol.log("ERROR: Geçersiz fileId: " + fileId);
                return null;
            }

            Protocol.log("DEBUG: cleanFileId: '" + cleanFileId + "'");

            String fileName = cleanFileId + Protocol.FILE_EXTENSION;
            String filePath = Protocol.DOCUMENTS_FOLDER + fileName;

            Protocol.log("DEBUG: Yükleme dosya yolu: " + filePath);
            Protocol.log("DEBUG: Mutlak yol: " + Paths.get(filePath).toAbsolutePath());

            // Dosya var mı kontrol et
            boolean fileExists = Utils.fileExists(filePath);
            Protocol.log("DEBUG: Utils.fileExists(): " + fileExists);

            // Java Files ile de kontrol et
            Path path = Paths.get(filePath);
            boolean javaExists = Files.exists(path);
            boolean javaReadable = Files.isReadable(path);
            boolean javaRegular = Files.isRegularFile(path);

            Protocol.log("DEBUG: Files.exists(): " + javaExists);
            Protocol.log("DEBUG: Files.isReadable(): " + javaReadable);
            Protocol.log("DEBUG: Files.isRegularFile(): " + javaRegular);

            if (javaExists) {
                try {
                    long size = Files.size(path);
                    Protocol.log("DEBUG: Dosya boyutu: " + size + " bytes");
                } catch (Exception e) {
                    Protocol.log("DEBUG: Dosya boyutu alınamadı: " + e.getMessage());
                }
            }

            // Dosya yoksa test dosyası oluştur
            if (!fileExists) {
                Protocol.log("DEBUG: Dosya bulunamadı, test dosyası oluşturuluyor...");
                createTestFileIfNeeded(cleanFileId);

                // Tekrar kontrol et
                fileExists = Utils.fileExists(filePath);
                Protocol.log("DEBUG: Test dosyası sonrası Utils.fileExists(): " + fileExists);
            }

            // Hala yoksa hata
            if (!fileExists) {
                Protocol.log("ERROR: Dosya hala bulunamadı: " + filePath);
                return null;
            }

            // Dosyayı oku
            Protocol.log("DEBUG: Dosya okunmaya başlanıyor...");
            String content = Utils.readFileContent(filePath);
            if (content == null) {
                Protocol.log("ERROR: Dosya içeriği okunamadı: " + filePath);
                return null;
            }

            Protocol.log("DEBUG: Dosya içeriği başarıyla okundu: " + content.length() + " karakter");
            Protocol.log("DEBUG: İçerik (ilk 100 karakter): '" +
                    (content.length() > 100 ? content.substring(0, 100) + "..." : content) + "'");

            // Document oluştur
            Document doc = new Document(cleanFileId, fileName, "system");
            doc.setContent(content);

            Protocol.log("SUCCESS: Doküman diskten yüklendi: " + cleanFileId + " (" + content.length() + " karakter)");
            Protocol.log("=== LOAD DOCUMENT DEBUG END ===");
            return doc;

        } catch (Exception e) {
            Protocol.logError("ERROR: Diskten yükleme hatası: " + fileId, e);
            return null;
        }
    }

    /**
     * Dokümanı kapat
     */
    public void closeDocument(String fileId, String userId) {
        Protocol.log("DEBUG: closeDocument çağrıldı - fileId: '" + fileId + "', userId: '" + userId + "'");

        if (fileId == null || userId == null) {
            return;
        }

        removeUserFromFile(fileId, userId);

        // Son kullanıcı ise kaydet ve memory'den kaldır
        Set<String> users = fileUsers.get(fileId);
        if (users == null || users.isEmpty()) {
            saveDocument(fileId);
            documents.remove(fileId);
            fileUsers.remove(fileId);
            Protocol.log("DEBUG: Son kullanıcıydı, dosya memory'den kaldırıldı: " + fileId);
        }

        Protocol.log("DEBUG: Doküman kapatıldı: " + fileId + " by " + userId);
    }

    // === ROBUST OPERATIONAL TRANSFORM INTEGRATION ===

    /**
     * Text insert - Robust OT ile çakışma çözümü
     */
    public synchronized boolean insertText(String fileId, int position, String text, String userId) {
        Document doc = documents.get(fileId);
        if (doc == null || text == null || text.isEmpty()) {
            return false;
        }

        // Position validation ve auto-fix
        String currentContent = doc.getContent();
        if (position < 0) position = 0;
        if (position > currentContent.length()) position = currentContent.length();

        // Yeni operasyon oluştur
        OperationalTransform.Operation newOp = OperationalTransform.createInsert(position, text, userId);

        // Son operasyonlara karşı transform et
        List<OperationalTransform.Operation> recentOps = doc.getRecentOperations(20);
        List<OperationalTransform.Operation> transformedOps =
                OperationalTransform.transformOperationList(newOp, recentOps);

        // Transform edilmiş operasyonları uygula
        boolean success = false;
        for (OperationalTransform.Operation transformedOp : transformedOps) {
            if (OperationalTransform.isValidOperation(transformedOp, doc.getContent())) {
                String newContent = OperationalTransform.applyOperation(doc.getContent(), transformedOp);
                doc.setContent(newContent);
                doc.addOperation(transformedOp);
                success = true;

                Protocol.log(String.format("Insert uygulandı: %s pos:%d->%d text:'%s'",
                        fileId, position, transformedOp.position, transformedOp.content));
            }
        }

        return success;
    }

    /**
     * Text delete - Robust OT ile çakışma çözümü
     */
    public synchronized boolean deleteText(String fileId, int position, int length, String userId) {
        Document doc = documents.get(fileId);
        if (doc == null || length <= 0) {
            return false;
        }

        // Position ve length validation
        String currentContent = doc.getContent();
        if (position < 0) position = 0;
        if (position >= currentContent.length()) return false;

        // Length auto-fix
        length = Math.min(length, currentContent.length() - position);
        if (length <= 0) return false;

        // Yeni operasyon oluştur
        OperationalTransform.Operation newOp = OperationalTransform.createDelete(position, length, userId);

        // Son operasyonlara karşı transform et
        List<OperationalTransform.Operation> recentOps = doc.getRecentOperations(20);
        List<OperationalTransform.Operation> transformedOps =
                OperationalTransform.transformOperationList(newOp, recentOps);

        // Transform edilmiş operasyonları uygula
        boolean success = false;
        for (OperationalTransform.Operation transformedOp : transformedOps) {
            if (OperationalTransform.isValidOperation(transformedOp, doc.getContent()) &&
                    transformedOp.length > 0) {

                String newContent = OperationalTransform.applyOperation(doc.getContent(), transformedOp);
                doc.setContent(newContent);
                doc.addOperation(transformedOp);
                success = true;

                Protocol.log(String.format("Delete uygulandı: %s pos:%d->%d len:%d->%d",
                        fileId, position, transformedOp.position, length, transformedOp.length));
            }
        }

        return success;
    }

    /**
     * Batch operasyon uygula - network gecikme kompensasyonu için
     */
    public synchronized List<OperationalTransform.Operation> applyBatchOperations(
            String fileId, List<OperationalTransform.Operation> clientOps, String userId) {

        Document doc = documents.get(fileId);
        if (doc == null || clientOps.isEmpty()) {
            return new ArrayList<>();
        }

        // Server'daki son operasyonları al
        List<OperationalTransform.Operation> serverOps = doc.getRecentOperations(50);

        // Batch transform
        List<OperationalTransform.Operation> transformedOps =
                OperationalTransform.transformBatch(clientOps, serverOps);

        // Transform edilmiş operasyonları uygula
        for (OperationalTransform.Operation op : transformedOps) {
            if (OperationalTransform.isValidOperation(op, doc.getContent())) {
                String newContent = OperationalTransform.applyOperation(doc.getContent(), op);
                doc.setContent(newContent);
                doc.addOperation(op);
            }
        }

        Protocol.log(String.format("Batch operasyonlar uygulandı: %s - %d ops by %s",
                fileId, transformedOps.size(), userId));

        return transformedOps;
    }

    // === FILE MANAGEMENT ===

    /**
     * Dokümanı kaydet
     */
    public boolean saveDocument(String fileId) {
        Document doc = documents.get(fileId);
        if (doc == null) {
            return false;
        }

        try {
            String filePath = Protocol.DOCUMENTS_FOLDER + fileId + Protocol.FILE_EXTENSION;
            Utils.writeFileContent(filePath, doc.getContent());
            doc.markSaved();

            Protocol.log("Doküman kaydedildi: " + fileId + " (" + doc.getContent().length() + " karakter)");
            return true;

        } catch (Exception e) {
            Protocol.logError("Kaydetme hatası: " + fileId, e);
            return false;
        }
    }

    /**
     * Tüm dirty dokümanları kaydet
     */
    private void autoSaveAll() {
        int savedCount = 0;

        for (String fileId : documents.keySet()) {
            Document doc = documents.get(fileId);
            if (doc != null && doc.isDirty()) {
                if (saveDocument(fileId)) {
                    savedCount++;
                }
            }
        }

        if (savedCount > 0) {
            Protocol.log("Auto-save: " + savedCount + " dosya kaydedildi");
        }
    }
    /**
     * DocumentManager'a eklenecek test dosyası oluşturma metodu
     */
    public void createTestFileIfNeeded(String fileId) {
        try {
            String filePath = Protocol.DOCUMENTS_FOLDER + fileId + Protocol.FILE_EXTENSION;
            Path path = Paths.get(filePath);

            Protocol.log("DEBUG: Test dosyası kontrol ediliyor: " + filePath);

            if (!Files.exists(path)) {
                // Test içeriği oluştur
                String testContent = "Bu bir test dosyasıdır.\n" +
                        "Dosya ID: " + fileId + "\n" +
                        "Oluşturulma tarihi: " + new java.util.Date() + "\n" +
                        "\nBu dosyayı düzenleyebilirsiniz.";

                // Klasörü oluştur
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }

                // Dosyayı oluştur
                Files.write(path, testContent.getBytes("UTF-8"));

                Protocol.log("DEBUG: Test dosyası oluşturuldu: " + filePath);
                Protocol.log("DEBUG: Dosya boyutu: " + Files.size(path) + " bytes");
            } else {
                Protocol.log("DEBUG: Dosya zaten mevcut: " + filePath);
                Protocol.log("DEBUG: Mevcut dosya boyutu: " + Files.size(path) + " bytes");
            }

        } catch (Exception e) {
            Protocol.logError("DEBUG: Test dosyası oluşturma hatası: " + fileId, e);
        }
    }

    /**
     * DocumentManager'a eklenecek debug metodu
     * Tüm dokümanları listele metodunu güncelleyin
     */
    public List<DocumentInfo> getAllDocuments() {
        List<DocumentInfo> result = new ArrayList<>();

        // DEBUGGING: Documents klasörünün mutlak yolunu yazdır
        String documentsPath = Protocol.DOCUMENTS_FOLDER;
        Protocol.log("DEBUG: Documents klasörü yolu: " + documentsPath);

        try {
            Path documentsDir = Paths.get(documentsPath);
            Protocol.log("DEBUG: Mutlak yol: " + documentsDir.toAbsolutePath());
            Protocol.log("DEBUG: Klasör var mı: " + Files.exists(documentsDir));

            if (Files.exists(documentsDir)) {
                Protocol.log("DEBUG: Klasördeki dosyalar:");
                Files.list(documentsDir)
                        .forEach(path -> Protocol.log("  - " + path.getFileName()));
            }
        } catch (IOException e) {
            Protocol.logError("DEBUG: Klasör listeleme hatası", e);
        }

        // Memory'deki dokümanlar
        Protocol.log("DEBUG: Memory'deki doküman sayısı: " + documents.size());
        for (Document doc : documents.values()) {
            DocumentInfo info = new DocumentInfo(
                    doc.getFileId(),
                    doc.getFileName(),
                    getFileUserCount(doc.getFileId()),
                    doc.getLastModified(),
                    doc.getContent().length()
            );
            result.add(info);
            Protocol.log("DEBUG: Memory'den eklendi: " + info.fileId + " -> " + info.fileName);
        }

        // Diskdeki dokümanlar (memory'de olmayan)
        try {
            Protocol.log("DEBUG: Disk dosyaları taranıyor...");
            Files.list(Paths.get(Protocol.DOCUMENTS_FOLDER))
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(Protocol.FILE_EXTENSION))
                    .forEach(path -> {
                        String fileName = path.getFileName().toString();
                        String fileId = fileName.replace(Protocol.FILE_EXTENSION, "");

                        Protocol.log("DEBUG: Disk dosyası bulundu: " + fileName + " (ID: " + fileId + ")");

                        if (!documents.containsKey(fileId)) {
                            try {
                                long lastModified = Files.getLastModifiedTime(path).toMillis();
                                long fileSize = Files.size(path);

                                DocumentInfo info = new DocumentInfo(fileId, fileName, 0, lastModified, (int)fileSize);
                                result.add(info);
                                Protocol.log("DEBUG: Disk'ten listeye eklendi: " + fileId + " -> " + fileName);
                            } catch (IOException e) {
                                Protocol.logError("DEBUG: Dosya bilgisi okunamadı: " + fileName, e);
                            }
                        } else {
                            Protocol.log("DEBUG: Dosya zaten memory'de var, skip: " + fileId);
                        }
                    });
        } catch (IOException e) {
            Protocol.logError("DEBUG: Dosya listeleme hatası", e);
        }

        // Son değişiklik tarihine göre sırala
        result.sort((a, b) -> Long.compare(b.lastModified, a.lastModified));

        Protocol.log("DEBUG: Toplam dosya sayısı döndürülüyor: " + result.size());
        for (DocumentInfo info : result) {
            Protocol.log("DEBUG: Final liste: " + info.fileId + " -> " + info.fileName + " (users: " + info.userCount + ")");
        }

        return result;
    }

    // === USER MANAGEMENT ===

    private void addUserToFile(String fileId, String userId) {
        fileUsers.computeIfAbsent(fileId, k -> ConcurrentHashMap.newKeySet()).add(userId);
    }

    private void removeUserFromFile(String fileId, String userId) {
        Set<String> users = fileUsers.get(fileId);
        if (users != null) {
            users.remove(userId);
        }
    }

    public List<String> getFileUsers(String fileId) {
        Set<String> users = fileUsers.get(fileId);
        return users != null ? new ArrayList<>(users) : new ArrayList<>();
    }

    public int getFileUserCount(String fileId) {
        Set<String> users = fileUsers.get(fileId);
        return users != null ? users.size() : 0;
    }

    // === CLEANUP ===

    public void shutdown() {
        Protocol.log("DocumentManager kapatılıyor...");

        // Tüm dokümanları kaydet
        autoSaveAll();

        // Auto-save executor'ı kapat
        autoSaveExecutor.shutdown();
        try {
            if (!autoSaveExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                autoSaveExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            autoSaveExecutor.shutdownNow();
        }

        // Memory'i temizle
        documents.clear();
        fileUsers.clear();

        Protocol.log("DocumentManager kapatıldı");
    }

    // === INNER CLASSES ===

    /**
     * Document info için basit data class
     */
    public static class DocumentInfo {
        public final String fileId;
        public final String fileName;
        public final int userCount;
        public final long lastModified;
        public final int size;

        public DocumentInfo(String fileId, String fileName, int userCount, long lastModified, int size) {
            this.fileId = fileId;
            this.fileName = fileName;
            this.userCount = userCount;
            this.lastModified = lastModified;
            this.size = size;
        }

        public String getFileId() { return fileId; }
        public String getFileName() { return fileName; }
        public int getUserCount() { return userCount; }
        public long getLastModified() { return lastModified; }
        public int getSize() { return size; }
    }

    /**
     * Document sınıfı - Operational Transform ile entegreli
     */
    public static class Document {
        private final String fileId;
        private final String fileName;
        private final String creatorUserId;
        private final long createdTime;

        private StringBuilder content;
        private long lastModified;
        private boolean dirty = false;

        // OT için operasyon geçmişi
        private final List<OperationalTransform.Operation> operationHistory =
                Collections.synchronizedList(new ArrayList<>());

        public Document(String fileId, String fileName, String creatorUserId) {
            this.fileId = fileId;
            this.fileName = fileName;
            this.creatorUserId = creatorUserId;
            this.createdTime = System.currentTimeMillis();
            this.lastModified = createdTime;
            this.content = new StringBuilder();
        }

        public void addOperation(OperationalTransform.Operation operation) {
            operationHistory.add(operation);

            // Geçmişi sınırla (son 100 operasyon)
            if (operationHistory.size() > 100) {
                operationHistory.remove(0);
            }
        }

        public List<OperationalTransform.Operation> getRecentOperations(int count) {
            synchronized (operationHistory) {
                int fromIndex = Math.max(0, operationHistory.size() - count);
                return new ArrayList<>(operationHistory.subList(fromIndex, operationHistory.size()));
            }
        }

        public Document copy() {
            Document copy = new Document(fileId, fileName, creatorUserId);
            copy.content = new StringBuilder(content.toString());
            copy.lastModified = lastModified;
            copy.dirty = dirty;
            return copy;
        }

        public void markSaved() {
            dirty = false;
        }

        // Getters & Setters
        public String getFileId() { return fileId; }
        public String getFileName() { return fileName; }
        public String getCreatorUserId() { return creatorUserId; }
        public String getContent() { return content.toString(); }

        public void setContent(String content) {
            this.content = new StringBuilder(content != null ? content : "");
            this.lastModified = System.currentTimeMillis();
            this.dirty = true;
        }

        public long getLastModified() { return lastModified; }
        public long getCreatedTime() { return createdTime; }
        public boolean isDirty() { return dirty; }
    }
}