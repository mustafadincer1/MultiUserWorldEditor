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
        if (fileId == null || userId == null) {
            return null;
        }

        Document doc = documents.get(fileId);

        // Memory'de yoksa diskten yükle
        if (doc == null) {
            doc = loadDocumentFromDisk(fileId);
            if (doc != null) {
                documents.put(fileId, doc);
            }
        }

        if (doc != null) {
            addUserToFile(fileId, userId);
            Protocol.log("Doküman açıldı: " + fileId + " by " + userId);
            return doc.copy(); // Thread-safe copy
        }

        return null;
    }

    /**
     * Diskten doküman yükle
     */
    private Document loadDocumentFromDisk(String fileId) {
        try {
            String filePath = Protocol.DOCUMENTS_FOLDER + fileId + Protocol.FILE_EXTENSION;

            if (!Utils.fileExists(filePath)) {
                Protocol.log("Dosya bulunamadı: " + filePath);
                return null;
            }

            String content = Utils.readFileContent(filePath);
            Document doc = new Document(fileId, fileId + Protocol.FILE_EXTENSION, "system");
            doc.setContent(content);

            Protocol.log("Doküman diskten yüklendi: " + fileId);
            return doc;

        } catch (Exception e) {
            Protocol.logError("Diskten yükleme hatası: " + fileId, e);
            return null;
        }
    }

    /**
     * Dokümanı kapat
     */
    public void closeDocument(String fileId, String userId) {
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
            Protocol.log("Doküman memory'den kaldırıldı: " + fileId);
        }

        Protocol.log("Doküman kapatıldı: " + fileId + " by " + userId);
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
     * Tüm dokümanları listele
     */
    public List<DocumentInfo> getAllDocuments() {
        List<DocumentInfo> result = new ArrayList<>();

        // Memory'deki dokümanlar
        for (Document doc : documents.values()) {
            result.add(new DocumentInfo(
                    doc.getFileId(),
                    doc.getFileName(),
                    getFileUserCount(doc.getFileId()),
                    doc.getLastModified(),
                    doc.getContent().length()
            ));
        }

        // Diskdeki dokümanlar (memory'de olmayan)
        try {
            Files.list(Paths.get(Protocol.DOCUMENTS_FOLDER))
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(Protocol.FILE_EXTENSION))
                    .forEach(path -> {
                        String fileName = path.getFileName().toString();
                        String fileId = fileName.replace(Protocol.FILE_EXTENSION, "");

                        if (!documents.containsKey(fileId)) {
                            try {
                                long lastModified = Files.getLastModifiedTime(path).toMillis();
                                long fileSize = Files.size(path);

                                result.add(new DocumentInfo(fileId, fileName, 0, lastModified, (int)fileSize));
                            } catch (IOException e) {
                                Protocol.logError("Dosya bilgisi okunamadı: " + fileName, e);
                            }
                        }
                    });
        } catch (IOException e) {
            Protocol.logError("Dosya listeleme hatası", e);
        }

        // Son değişiklik tarihine göre sırala
        result.sort((a, b) -> Long.compare(b.lastModified, a.lastModified));

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