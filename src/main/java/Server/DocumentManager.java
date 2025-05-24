package Server;

import Common.Protocol;
import Common.Utils;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Çok kullanıcılı doküman yönetim sistemi
 * Dosyaları memory'de tutar, çok kullanıcılı erişimi koordine eder
 * Operational Transform ile çakışmaları çözer
 */
public class DocumentManager {

    // Ana doküman storage
    private final Map<String, Document> documents = new ConcurrentHashMap<>();

    // Dosya kilitleri (her dosya için ayrı lock)
    private final Map<String, ReentrantReadWriteLock> fileLocks = new ConcurrentHashMap<>();

    // Dosya kullanıcı mapping (hangi dosyayı kimler açmış)
    private final Map<String, Set<String>> fileUsers = new ConcurrentHashMap<>();

    // Auto-save executor
    private final ScheduledExecutorService autoSaveExecutor;

    // Dosya sayacı (unique ID için)
    private final AtomicLong fileCounter = new AtomicLong(1);

    /**
     * Constructor
     */
    public DocumentManager() {
        // Auto-save scheduler başlat
        this.autoSaveExecutor = Executors.newScheduledThreadPool(2);
        startAutoSaveScheduler();

        // Documents klasörünü oluştur
        createDocumentsDirectory();

        Utils.log("DocumentManager başlatıldı");
    }

    /**
     * Documents klasörünü oluştur
     */
    private void createDocumentsDirectory() {
        try {
            Path documentsPath = Paths.get(Protocol.DOCUMENTS_FOLDER);
            if (!Files.exists(documentsPath)) {
                Files.createDirectories(documentsPath);
                Utils.log("Documents klasörü oluşturuldu: " + Protocol.DOCUMENTS_FOLDER);
            }
        } catch (IOException e) {
            Utils.logError("Documents klasörü oluşturulamadı", e);
        }
    }

    /**
     * Otomatik kaydetme scheduler'ını başlat
     */
    private void startAutoSaveScheduler() {
        autoSaveExecutor.scheduleAtFixedRate(() -> {
            try {
                autoSaveAllDocuments();
            } catch (Exception e) {
                Utils.logError("Auto-save hatası", e);
            }
        }, Protocol.AUTO_SAVE_INTERVAL, Protocol.AUTO_SAVE_INTERVAL, TimeUnit.MILLISECONDS);
    }

    /**
     * Yeni doküman oluştur
     */
    public String createDocument(String fileName, String creatorUserId) {
        if (!Protocol.isValidFilename(fileName)) {
            Utils.log("Geçersiz dosya ismi: " + fileName);
            return null;
        }

        try {
            // Unique file ID oluştur
            String fileId = Protocol.generateFileId();

            // Dosya uzantısı kontrolü ve ekleme
            if (!Protocol.isSupportedExtension(fileName)) {
                fileName += Protocol.DEFAULT_EXTENSION;
            }

            // Yeni doküman oluştur
            Document document = new Document(fileId, fileName, creatorUserId);

            // Lock oluştur
            fileLocks.put(fileId, new ReentrantReadWriteLock());

            // Storage'a ekle
            documents.put(fileId, document);

            // Kullanıcıyı dosyaya ekle
            addUserToFile(fileId, creatorUserId);

            Utils.log("Doküman oluşturuldu: " + fileName + " (" + fileId + ") by " + creatorUserId);

            return fileId;

        } catch (Exception e) {
            Utils.logError("Doküman oluşturma hatası: " + fileName, e);
            return null;
        }
    }

    /**
     * Mevcut dokümanı aç
     */
    public Document openDocument(String fileId, String userId) {
        if (fileId == null || userId == null) {
            return null;
        }

        ReentrantReadWriteLock lock = fileLocks.get(fileId);
        if (lock == null) {
            // Dosya mevcut değil - diskten yüklemeyi dene
            return loadDocumentFromDisk(fileId, userId);
        }

        lock.readLock().lock();
        try {
            Document document = documents.get(fileId);
            if (document != null) {
                // Kullanıcıyı dosyaya ekle
                addUserToFile(fileId, userId);

                Utils.log("Doküman açıldı: " + fileId + " by " + userId);
                return document.copy(); // Thread-safe copy döndür
            }

            return null;

        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Diskten doküman yükle
     */
    private Document loadDocumentFromDisk(String fileId, String userId) {
        try {
            // Dosya yolu oluştur
            String filePath = Protocol.DOCUMENTS_FOLDER + fileId + Protocol.DEFAULT_EXTENSION;

            if (!Utils.fileExists(filePath)) {
                Utils.log("Dosya bulunamadı: " + filePath);
                return null;
            }

            // Dosya içeriğini oku
            String content = Utils.readFileContent(filePath);

            // Document oluştur
            Document document = new Document(fileId, fileId + Protocol.DEFAULT_EXTENSION, userId);
            document.setContent(content);

            // Lock oluştur
            fileLocks.put(fileId, new ReentrantReadWriteLock());

            // Memory'e yükle
            documents.put(fileId, document);

            // Kullanıcıyı ekle
            addUserToFile(fileId, userId);

            Utils.log("Doküman diskten yüklendi: " + fileId);

            return document.copy();

        } catch (Exception e) {
            Utils.logError("Diskten doküman yükleme hatası: " + fileId, e);
            return null;
        }
    }

    /**
     * Dokümanı kapat (kullanıcı için)
     */
    public void closeDocument(String fileId, String userId) {
        if (fileId == null || userId == null) {
            return;
        }

        removeUserFromFile(fileId, userId);

        // Eğer dosyayı kullanan kimse kalmadıysa memory'den kaldır
        Set<String> users = fileUsers.get(fileId);
        if (users == null || users.isEmpty()) {
            // Son kullanıcı - dosyayı kaydet ve memory'den kaldır
            saveDocument(fileId);

            documents.remove(fileId);
            fileLocks.remove(fileId);
            fileUsers.remove(fileId);

            Utils.log("Doküman memory'den kaldırıldı: " + fileId);
        }

        Utils.log("Doküman kapatıldı: " + fileId + " by " + userId);
    }

    /**
     * Metne ekleme yap (Operational Transform ile)
     */
    public boolean insertText(String fileId, int position, String text, String userId) {
        if (!validateTextOperation(fileId, position, text)) {
            return false;
        }

        ReentrantReadWriteLock lock = fileLocks.get(fileId);
        if (lock == null) {
            return false;
        }

        lock.writeLock().lock();
        try {
            Document document = documents.get(fileId);
            if (document == null) {
                return false;
            }

            // Gelişmiş Operational Transform uygula
            TextOperation textOperation = new TextOperation(TextOperation.Type.INSERT, position, text, userId);
            OperationalTransform.Operation transformedOp = transformOperationAdvanced(document, textOperation);

            // Transform edilmiş operasyonu uygula
            boolean success = document.applyInsert(transformedOp.position, transformedOp.text);

            if (success) {
                // Yeni operasyonu geçmişe ekle (transformed versiyonu)
                TextOperation newHistoryOp = new TextOperation(TextOperation.Type.INSERT,
                        transformedOp.position, transformedOp.text, userId);
                document.addOperation(newHistoryOp);

                Utils.log("Advanced Text insert başarılı: " + fileId + " pos:" + transformedOp.position +
                        " (original: " + position + ") text:'" + transformedOp.text + "' by " + userId);
            }

            return success;

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Metinden silme yap (Operational Transform ile)
     */
    public boolean deleteText(String fileId, int position, int length, String userId) {
        if (!validateDeleteOperation(fileId, position, length)) {
            return false;
        }

        ReentrantReadWriteLock lock = fileLocks.get(fileId);
        if (lock == null) {
            return false;
        }

        lock.writeLock().lock();
        try {
            Document document = documents.get(fileId);
            if (document == null) {
                return false;
            }

            // Silinecek metni al
            String deletedText = document.getContent().substring(position, position + length);

            // Gelişmiş Operational Transform uygula
            TextOperation textOperation = new TextOperation(TextOperation.Type.DELETE, position, deletedText, userId);
            OperationalTransform.Operation transformedOp = transformOperationAdvanced(document, textOperation);

            // Transform edilmiş operasyonu uygula
            boolean success = document.applyDelete(transformedOp.position, transformedOp.length);

            if (success) {
                // Yeni operasyonu geçmişe ekle (transformed versiyonu)
                TextOperation newHistoryOp = new TextOperation(TextOperation.Type.DELETE,
                        transformedOp.position, transformedOp.length + "", userId); // length'i string'e çevir
                document.addOperation(newHistoryOp);

                Utils.log("Advanced Text delete başarılı: " + fileId + " pos:" + transformedOp.position +
                        " (original: " + position + ") len:" + transformedOp.length + " by " + userId);
            }

            return success;

        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * Gelişmiş Operational Transform (OperationalTransform sınıfını kullanır)
     */
    private OperationalTransform.Operation transformOperationAdvanced(Document document, TextOperation oldOp) {
        // TextOperation'ı OperationalTransform.Operation'a çevir
        OperationalTransform.Operation newOp;
        if (oldOp.type == TextOperation.Type.INSERT) {
            newOp = new OperationalTransform.Operation(
                    OperationalTransform.OperationType.INSERT,
                    oldOp.position, oldOp.text, oldOp.userId, oldOp.timestamp);
        } else {
            newOp = new OperationalTransform.Operation(
                    OperationalTransform.OperationType.DELETE,
                    oldOp.position, oldOp.text.length(), oldOp.userId, oldOp.timestamp);
        }

        // Son 10 operasyona karşı transform et
        List<TextOperation> history = document.getRecentOperations(10);

        for (TextOperation historyOp : history) {
            OperationalTransform.Operation serverOp;
            if (historyOp.type == TextOperation.Type.INSERT) {
                serverOp = new OperationalTransform.Operation(
                        OperationalTransform.OperationType.INSERT,
                        historyOp.position, historyOp.text, historyOp.userId, historyOp.timestamp);
            } else {
                serverOp = new OperationalTransform.Operation(
                        OperationalTransform.OperationType.DELETE,
                        historyOp.position, historyOp.text.length(), historyOp.userId, historyOp.timestamp);
            }

            // Transform işlemi
            newOp = OperationalTransform.transform(newOp, serverOp, false);
        }

        return newOp;
    }

    /**
     * Basit Operational Transform (eski versiyon - backward compatibility için)
     * Artık kullanılmıyor, gelişmiş OT kullanıyoruz
     */
    @Deprecated
    private TextOperation transformOperation(Document document, TextOperation newOp) {
        List<TextOperation> history = document.getRecentOperations(10); // Son 10 operasyon

        TextOperation transformedOp = newOp;

        // Her bir geçmiş operasyonla transform et
        for (TextOperation historyOp : history) {
            transformedOp = transform(transformedOp, historyOp);
        }

        return transformedOp;
    }

    /**
     * İki operasyonu transform eder (basit OT algoritması)
     */
    private TextOperation transform(TextOperation op1, TextOperation op2) {
        // Aynı kullanıcının operasyonları transform edilmez
        if (op1.userId.equals(op2.userId)) {
            return op1;
        }

        // INSERT - INSERT transform
        if (op1.type == TextOperation.Type.INSERT && op2.type == TextOperation.Type.INSERT) {
            if (op2.position <= op1.position) {
                // op2, op1'den önce - op1'in pozisyonunu kaydır
                return new TextOperation(op1.type, op1.position + op2.text.length(), op1.text, op1.userId);
            }
            // op2, op1'den sonra - değişiklik yok
            return op1;
        }

        // INSERT - DELETE transform
        if (op1.type == TextOperation.Type.INSERT && op2.type == TextOperation.Type.DELETE) {
            if (op2.position <= op1.position) {
                // op2, op1'den önce silme yaptı - op1'in pozisyonunu kaydır
                int newPosition = Math.max(op2.position, op1.position - op2.text.length());
                return new TextOperation(op1.type, newPosition, op1.text, op1.userId);
            }
            // op2, op1'den sonra - değişiklik yok
            return op1;
        }

        // DELETE - INSERT transform
        if (op1.type == TextOperation.Type.DELETE && op2.type == TextOperation.Type.INSERT) {
            if (op2.position <= op1.position) {
                // op2, op1'den önce ekleme yaptı - op1'in pozisyonunu kaydır
                return new TextOperation(op1.type, op1.position + op2.text.length(), op1.text, op1.userId);
            }
            // op2, op1'den sonra - değişiklik yok
            return op1;
        }

        // DELETE - DELETE transform
        if (op1.type == TextOperation.Type.DELETE && op2.type == TextOperation.Type.DELETE) {
            if (op2.position < op1.position) {
                // op2, op1'den önce silme yaptı
                int overlap = Math.min(op1.position, op2.position + op2.text.length()) - op2.position;
                if (overlap > 0) {
                    // Çakışma var - op1'i ayarla
                    int newPosition = op2.position;
                    int newLength = Math.max(0, op1.text.length() - overlap);
                    String newText = newLength > 0 ? op1.text.substring(overlap) : "";
                    return new TextOperation(op1.type, newPosition, newText, op1.userId);
                } else {
                    // Çakışma yok - sadece pozisyonu kaydır
                    return new TextOperation(op1.type, op1.position - op2.text.length(), op1.text, op1.userId);
                }
            }
            // op2, op1'den sonra - değişiklik yok
            return op1;
        }

        return op1; // Default: değişiklik yok
    }

    /**
     * Dokümanı diske kaydet
     */
    public boolean saveDocument(String fileId) {
        if (fileId == null) {
            return false;
        }

        ReentrantReadWriteLock lock = fileLocks.get(fileId);
        if (lock == null) {
            return false;
        }

        lock.readLock().lock();
        try {
            Document document = documents.get(fileId);
            if (document == null) {
                return false;
            }

            // Dosya yolu oluştur
            String filePath = Protocol.DOCUMENTS_FOLDER + fileId + Protocol.DEFAULT_EXTENSION;

            // İçeriği diske yaz
            Utils.writeFileContent(filePath, document.getContent());

            // Son kaydetme zamanını güncelle
            document.updateLastSaved();

            Utils.log("Doküman kaydedildi: " + fileId + " (" + document.getContent().length() + " karakter)");

            return true;

        } catch (Exception e) {
            Utils.logError("Doküman kaydetme hatası: " + fileId, e);
            return false;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * Tüm dokümanları otomatik kaydet
     */
    private void autoSaveAllDocuments() {
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
            Utils.log("Auto-save tamamlandı: " + savedCount + " dosya kaydedildi");
        }
    }

    /**
     * Tüm dokümanların listesini al
     */
    public List<DocumentInfo> getAllDocuments() {
        List<DocumentInfo> documentList = new ArrayList<>();

        // Memory'deki dokümanlar
        for (Document doc : documents.values()) {
            DocumentInfo info = new DocumentInfo(
                    doc.getFileId(),
                    doc.getFileName(),
                    getFileUserCount(doc.getFileId()),
                    doc.getLastModified(),
                    doc.getContent().length()
            );
            documentList.add(info);
        }

        // Diskdeki dokümanlar (memory'de olmayan)
        try {
            List<String> diskFiles = Utils.listFiles(Protocol.DOCUMENTS_FOLDER);

            for (String fileName : diskFiles) {
                if (Protocol.isSupportedExtension(fileName)) {
                    String fileId = Utils.removeFileExtension(fileName);

                    // Memory'de yoksa ekle
                    if (!documents.containsKey(fileId)) {
                        String filePath = Protocol.DOCUMENTS_FOLDER + fileName;
                        long lastModified = Files.getLastModifiedTime(Paths.get(filePath)).toMillis();
                        long fileSize = Files.size(Paths.get(filePath));

                        DocumentInfo info = new DocumentInfo(fileId, fileName, 0, lastModified, (int)fileSize);
                        documentList.add(info);
                    }
                }
            }

        } catch (Exception e) {
            Utils.logError("Disk dosyaları listeleme hatası", e);
        }

        // Listeyi son değişiklik tarihine göre sırala
        documentList.sort((a, b) -> Long.compare(b.lastModified, a.lastModified));

        return documentList;
    }

    /**
     * Kullanıcıyı dosyaya ekle
     */
    private void addUserToFile(String fileId, String userId) {
        fileUsers.computeIfAbsent(fileId, k -> ConcurrentHashMap.newKeySet()).add(userId);
    }

    /**
     * Kullanıcıyı dosyadan çıkar
     */
    private void removeUserFromFile(String fileId, String userId) {
        Set<String> users = fileUsers.get(fileId);
        if (users != null) {
            users.remove(userId);
        }
    }

    /**
     * Dosyayı kullanan kullanıcıları al
     */
    public List<String> getFileUsers(String fileId) {
        Set<String> users = fileUsers.get(fileId);
        return users != null ? new ArrayList<>(users) : new ArrayList<>();
    }

    /**
     * Dosya kullanıcı sayısını al
     */
    public int getFileUserCount(String fileId) {
        Set<String> users = fileUsers.get(fileId);
        return users != null ? users.size() : 0;
    }

    /**
     * Açık dosya sayısını al
     */
    public int getOpenFileCount() {
        return documents.size();
    }

    /**
     * Validation metotları
     */
    private boolean validateTextOperation(String fileId, int position, String text) {
        if (fileId == null || text == null) {
            return false;
        }

        if (!Protocol.isValidTextPosition(position) || !Protocol.isValidInsertLength(text.length())) {
            return false;
        }

        Document doc = documents.get(fileId);
        return doc != null && position <= doc.getContent().length();
    }

    private boolean validateDeleteOperation(String fileId, int position, int length) {
        if (fileId == null) {
            return false;
        }

        if (!Protocol.isValidTextPosition(position) || !Protocol.isValidDeleteLength(length)) {
            return false;
        }

        Document doc = documents.get(fileId);
        return doc != null && position + length <= doc.getContent().length();
    }

    /**
     * Cleanup - kapatma işlemleri
     */
    public void shutdown() {
        Utils.log("DocumentManager kapatılıyor...");

        // Tüm dokümanları kaydet
        autoSaveAllDocuments();

        // Auto-save scheduler'ını durdur
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
        fileLocks.clear();
        fileUsers.clear();

        Utils.log("DocumentManager kapatıldı");
    }

    // === İÇ SINIFLAR ===

    /**
     * Doküman bilgi sınıfı
     */
    public static class DocumentInfo {
        private final String fileId;
        private final String fileName;
        private final int userCount;
        private final long lastModified;
        private final int size;

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
     * Doküman sınıfı
     */
    public static class Document {
        private final String fileId;
        private final String fileName;
        private final String creatorUserId;
        private final long createdTime;

        private StringBuilder content;
        private long lastModified;
        private long lastSaved;
        private boolean dirty = false;

        // Operational Transform için operasyon geçmişi
        private final List<TextOperation> operationHistory = new ArrayList<>();
        private final Object historyLock = new Object();

        public Document(String fileId, String fileName, String creatorUserId) {
            this.fileId = fileId;
            this.fileName = fileName;
            this.creatorUserId = creatorUserId;
            this.createdTime = System.currentTimeMillis();
            this.lastModified = createdTime;
            this.lastSaved = 0;
            this.content = new StringBuilder();
        }

        public boolean applyInsert(int position, String text) {
            if (position < 0 || position > content.length()) {
                return false;
            }

            content.insert(position, text);
            lastModified = System.currentTimeMillis();
            dirty = true;
            return true;
        }

        public boolean applyDelete(int position, int length) {
            if (position < 0 || position + length > content.length()) {
                return false;
            }

            content.delete(position, position + length);
            lastModified = System.currentTimeMillis();
            dirty = true;
            return true;
        }

        public void addOperation(TextOperation operation) {
            synchronized (historyLock) {
                operationHistory.add(operation);

                // Geçmişi sınırla (son 50 operasyon)
                if (operationHistory.size() > 50) {
                    operationHistory.remove(0);
                }
            }
        }

        public List<TextOperation> getRecentOperations(int count) {
            synchronized (historyLock) {
                int fromIndex = Math.max(0, operationHistory.size() - count);
                return new ArrayList<>(operationHistory.subList(fromIndex, operationHistory.size()));
            }
        }

        public Document copy() {
            Document copy = new Document(fileId, fileName, creatorUserId);
            copy.content = new StringBuilder(content.toString());
            copy.lastModified = lastModified;
            copy.lastSaved = lastSaved;
            copy.dirty = dirty;
            return copy;
        }

        public void updateLastSaved() {
            lastSaved = System.currentTimeMillis();
            dirty = false;
        }

        // Getters
        public String getFileId() { return fileId; }
        public String getFileName() { return fileName; }
        public String getCreatorUserId() { return creatorUserId; }
        public String getContent() { return content.toString(); }
        public void setContent(String content) {
            this.content = new StringBuilder(content);
            this.lastModified = System.currentTimeMillis();
            this.dirty = true;
        }
        public long getLastModified() { return lastModified; }
        public long getCreatedTime() { return createdTime; }
        public boolean isDirty() { return dirty; }
    }

    /**
     * Text operasyon sınıfı (Operational Transform için)
     */
    public static class TextOperation {
        public enum Type { INSERT, DELETE }

        public final Type type;
        public final int position;
        public final String text;
        public final String userId;
        public final long timestamp;

        public TextOperation(Type type, int position, String text, String userId) {
            this.type = type;
            this.position = position;
            this.text = text;
            this.userId = userId;
            this.timestamp = System.currentTimeMillis();
        }
    }
}