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
 * UPDATED: fileName - fileId.txt formatında kayıt
 */
public class DocumentManager {

    // Ana document storage
    private final Map<String, Document> documents = new ConcurrentHashMap<>();

    // Dosya kullanıcı mapping
    private final Map<String, Set<String>> fileUsers = new ConcurrentHashMap<>();

    // Auto-save için executor (basit versiyon)
    private final ScheduledExecutorService autoSaveExecutor;

    /**
     * Constructor - UPDATED: Debug bilgileri eklendi
     */
    public DocumentManager() {
        // 🔧 NEW: Sistem bilgilerini logla
        Protocol.logSystemInfo();
        Protocol.logCurrentWorkingDirectory();

        // Documents klasörü oluştur
        createDocumentsDirectory();

        // 🔧 NEW: Documents klasörü durumunu kontrol et
        Protocol.checkDocumentsFolder();

        // Auto-save her 30 saniyede bir
        this.autoSaveExecutor = Executors.newSingleThreadScheduledExecutor();
        this.autoSaveExecutor.scheduleAtFixedRate(this::autoSaveAll, 30, 30, TimeUnit.SECONDS);

        Protocol.log("DocumentManager başlatıldı (Robust OT ile)");
    }

    public static class InsertResult {
        public final boolean success;
        public final int appliedPosition;
        public final String appliedText;

        public InsertResult(boolean success, int appliedPosition, String appliedText) {
            this.success = success;
            this.appliedPosition = appliedPosition;
            this.appliedText = appliedText;
        }
    }

    public synchronized InsertResult insertTextWithResult(String fileId, int position, String text, String userId) {
        Protocol.log("=== ENHANCED INSERT TEXT WITH RESULT DEBUG ===");
        Protocol.log("DEBUG: insertTextWithResult - fileId: " + fileId + ", original position: " + position +
                ", text: '" + text.replace("\n", "\\n") + "', userId: " + userId);

        Document doc = documents.get(fileId);
        if (doc == null || text == null || text.length() == 0) {
            Protocol.log("ERROR: Invalid parameters");
            return new InsertResult(false, position, text != null ? text : "");
        }

        String currentContent = doc.getContent();
        int originalPosition = position;

        Protocol.log("DEBUG: Current content length: " + currentContent.length());
        Protocol.log("DEBUG: Original position: " + originalPosition);

        // 🔧 STRICT POSITION CLAMPING
        int clampedPosition = Math.max(0, Math.min(position, currentContent.length()));
        Protocol.log("DEBUG: Clamped position: " + clampedPosition);

        // 🔧 SPECIAL LOGGING FOR NEWLINE
        if (text.equals("\n")) {
            Protocol.log("🔥 NEWLINE INSERT DETECTED - original pos: " + originalPosition +
                    ", clamped pos: " + clampedPosition);
        }

        // Yeni operasyon oluştur
        OperationalTransform.Operation newOp = OperationalTransform.createInsert(clampedPosition, text, userId);
        Protocol.log("DEBUG: Created operation: " + newOp);

        // 🔧 SIGNIFICANTLY REDUCED TRANSFORM SCOPE for INSERT operations
        int maxHistoricalOps = text.equals("\n") ? 2 : 3; // Even fewer for NEWLINE
        List<OperationalTransform.Operation> recentOps = doc.getRecentOperations(maxHistoricalOps);
        Protocol.log("DEBUG: Recent operations count: " + recentOps.size() + " (max: " + maxHistoricalOps + ")");

        List<OperationalTransform.Operation> transformedOps =
                OperationalTransform.transformOperationList(newOp, recentOps);
        Protocol.log("DEBUG: Transformed operations count: " + transformedOps.size());

        // Transform edilmiş operasyonları uygula
        boolean success = false;
        int appliedPosition = clampedPosition;

        for (OperationalTransform.Operation transformedOp : transformedOps) {
            Protocol.log("DEBUG: Checking transformed op: " + transformedOp);

            // 🔧 ENHANCED CONTENT-AWARE VALIDATION with aggressive position fixing
            String currentState = doc.getContent();
            int currentStateLength = currentState.length();

            Protocol.log("DEBUG: Content state - length: " + currentStateLength +
                    ", op position: " + transformedOp.position);

            // 🔧 AGGRESSIVE AUTO-FIX FOR OUT-OF-BOUNDS OPERATIONS
            OperationalTransform.Operation finalOp = transformedOp;

            if (transformedOp.position > currentStateLength) {
                // Position beyond content - clamp to end
                int newPos = currentStateLength;
                // 🔧 FIXED: Use correct INSERT constructor (Type, position, content, userId)
                finalOp = new OperationalTransform.Operation(OperationalTransform.Operation.Type.INSERT,
                        newPos, transformedOp.content, transformedOp.userId);
                Protocol.log("DEBUG: Position auto-fixed: " + transformedOp.position + " → " + newPos);

            } else if (transformedOp.position < 0) {
                // Negative position - clamp to start
                // 🔧 FIXED: Use correct INSERT constructor (Type, position, content, userId)
                finalOp = new OperationalTransform.Operation(OperationalTransform.Operation.Type.INSERT,
                        0, transformedOp.content, transformedOp.userId);
                Protocol.log("DEBUG: Negative position fixed: " + transformedOp.position + " → 0");
            }

            // Final validation
            boolean isValid = OperationalTransform.isValidOperation(finalOp, currentState);
            Protocol.log("DEBUG: isValidOperation: " + isValid + " (content length: " + currentStateLength + ")");

            if (isValid) {
                String oldContent = doc.getContent();
                String newContent = OperationalTransform.applyOperation(oldContent, finalOp);
                doc.setContent(newContent);
                doc.addOperation(finalOp);
                success = true;
                appliedPosition = finalOp.position;

                Protocol.log(String.format("SUCCESS: Insert applied - %s original pos:%d → clamped pos:%d → applied pos:%d text:'%s'",
                        fileId, originalPosition, clampedPosition, appliedPosition,
                        text.replace("\n", "\\n")));
                Protocol.log("DEBUG: Content changed from length " + oldContent.length() +
                        " to " + newContent.length());

                // 🔧 SPECIAL SUCCESS LOGGING FOR NEWLINE
                if (text.equals("\n")) {
                    Protocol.log("🎉 SERVER SUCCESS: NEWLINE CHARACTER INSERTED! 🎉");
                    Protocol.log("NEWLINE: " + fileId + " original pos:" + originalPosition +
                            " → applied pos:" + appliedPosition + " by " + userId);
                }

            } else {
                Protocol.log("WARNING: Operation failed validation even after aggressive auto-fix");
                Protocol.log("DEBUG: Operation failed validation - likely position out of bounds");
                Protocol.log("DEBUG: Op position: " + finalOp.position + ", content length: " + currentStateLength);

                // 🔧 LAST RESORT: Insert at end of content
                if (currentStateLength >= 0) {
                    OperationalTransform.Operation fallbackOp =
                            OperationalTransform.createInsert(currentStateLength, text, userId);

                    if (OperationalTransform.isValidOperation(fallbackOp, currentState)) {
                        String oldContent = doc.getContent();
                        String newContent = OperationalTransform.applyOperation(oldContent, fallbackOp);
                        doc.setContent(newContent);
                        doc.addOperation(fallbackOp);
                        success = true;
                        appliedPosition = currentStateLength;

                        Protocol.log("SUCCESS: Fallback insert applied at end position " + currentStateLength);
                    }
                }
            }
        }

        Protocol.log("DEBUG: Final success: " + success + ", applied position: " + appliedPosition);
        Protocol.log("=== ENHANCED INSERT RESULT END ===");
        return new InsertResult(success, appliedPosition, text);
    }

    public synchronized boolean deleteDocument(String fileId, String requestingUserId) {
        Protocol.log("=== DELETE DOCUMENT DEBUG ===");
        Protocol.log("DEBUG: deleteDocument - fileId: '" + fileId + "', requestingUserId: '" + requestingUserId + "'");

        if (fileId == null || fileId.trim().isEmpty()) {
            Protocol.log("ERROR: FileId null veya boş");
            return false;
        }

        if (requestingUserId == null || requestingUserId.trim().isEmpty()) {
            Protocol.log("ERROR: RequestingUserId null veya boş");
            return false;
        }

        try {
            // 1. Memory'de döküman var mı kontrol et
            Document doc = documents.get(fileId);
            String fileName = null;

            if (doc != null) {
                fileName = doc.getFileName();

                // 2. Döküman şu anda açık mı kontrol et
                Set<String> users = fileUsers.get(fileId);
                if (users != null && users.size() > 1) {
                    Protocol.log("ERROR: Döküman şu anda " + users.size() + " kullanıcı tarafından kullanılıyor");
                    return false;
                }

                Protocol.log("DEBUG: Memory'den döküman bulundu: " + fileName);
            } else {
                Protocol.log("DEBUG: Memory'de döküman yok, disk'ten fileName bulunmaya çalışılıyor");

                // Memory'de yoksa disk'ten fileName'i bul
                fileName = findFileNameFromDisk(fileId);
                if (fileName == null) {
                    Protocol.log("ERROR: Döküman disk'te de bulunamadı");
                    return false;
                }
            }

            // 4. Disk'ten dosyayı sil
            boolean diskDeleted = deleteDocumentFromDisk(fileId, fileName);
            if (!diskDeleted) {
                Protocol.log("ERROR: Disk'ten dosya silinemedi");
                return false;
            }

            // 5. Memory'den temizle
            if (doc != null) {
                documents.remove(fileId);
                Protocol.log("DEBUG: Memory'den döküman kaldırıldı");
            }

            // 6. User mapping'lerini temizle
            fileUsers.remove(fileId);
            Protocol.log("DEBUG: File user mappings temizlendi");

            Protocol.log("SUCCESS: Döküman başarıyla silindi: " + fileName + " (" + fileId + ") by " + requestingUserId);
            Protocol.log("=== DELETE DOCUMENT END ===");
            return true;

        } catch (Exception e) {
            Protocol.logError("ERROR: Döküman silme hatası: " + fileId, e);
            return false;
        }
    }

    /**
     * 🔧 NEW: Disk'ten fileName bulma
     */
    private String findFileNameFromDisk(String fileId) {
        try {
            Path documentsDir = Paths.get(Protocol.DOCUMENTS_FOLDER);
            if (!Files.exists(documentsDir)) {
                return null;
            }

            String targetPattern = " - " + fileId + ".txt";

            Path matchingFile = Files.list(documentsDir)
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(targetPattern))
                    .findFirst()
                    .orElse(null);

            if (matchingFile != null) {
                String diskFileName = matchingFile.getFileName().toString();
                return extractOriginalFileName(diskFileName, fileId);
            }

            return null;

        } catch (Exception e) {
            Protocol.log("DEBUG: findFileNameFromDisk exception: " + e.getMessage());
            return null;
        }
    }

    /**
     * 🔧 NEW: Disk'ten dosya silme
     */
    private boolean deleteDocumentFromDisk(String fileId, String fileName) {
        try {
            // Disk dosya adını oluştur
            String diskFileName = fileName + " - " + fileId + ".txt";
            String filePath = Protocol.DOCUMENTS_FOLDER + diskFileName;

            Protocol.log("DEBUG: Silinecek dosya yolu: " + filePath);

            Path path = Paths.get(filePath);

            if (!Files.exists(path)) {
                Protocol.log("WARNING: Silinecek dosya disk'te bulunamadı: " + filePath);
                return true; // Dosya zaten yok, başarılı sayalım
            }

            // Dosyayı sil
            Files.delete(path);

            Protocol.log("SUCCESS: Dosya disk'ten silindi: " + diskFileName);
            return true;

        } catch (Exception e) {
            Protocol.logError("ERROR: Disk'ten dosya silme hatası: " + fileId, e);
            return false;
        }
    }

    /**
     * 🔧 NEW: Dökümanın silinebilir olup olmadığını kontrol et
     * UPDATED: Owner check kaldırıldı - herkes silebilir
     */
    public boolean canDeleteDocument(String fileId, String requestingUserId) {
        Document doc = documents.get(fileId);

        if (doc == null) {
            // Memory'de yok, disk'te var mı?
            String fileName = findFileNameFromDisk(fileId);
            return fileName != null;
        }

        // Aktif kullanıcı kontrolü - sadece 1'den fazla kullanıcı varsa silme yapılamaz
        Set<String> users = fileUsers.get(fileId);
        return users == null || users.size() <= 1;
    }

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
     * Yeni doküman oluştur - UPDATED: Hemen disk'e kaydet
     */
    public String createDocument(String fileName, String creatorUserId) {
        if (!Protocol.isValidFilename(fileName)) {
            Protocol.log("Geçersiz dosya ismi: " + fileName);
            return null;
        }

        try {
            // Unique file ID
            String fileId = Protocol.generateFileId();

            // 🔧 UPDATED: Dosya uzantısı kontrolü kaldırıldı (artık fileName - fileId.txt formatında)
            String cleanFileName = fileName;

            // Yeni document
            Document document = new Document(fileId, cleanFileName, creatorUserId);

            // 🔧 NEW: Varsayılan içerik ekle
            String defaultContent = ""; // Boş dosya olarak başlat
            document.setContent(defaultContent);

            // Memory'e ekle
            documents.put(fileId, document);

            // Kullanıcıyı ekle
            addUserToFile(fileId, creatorUserId);

            // 🔧 NEW: Hemen disk'e kaydet
            boolean saved = saveDocument(fileId);
            if (saved) {
                Protocol.log("SUCCESS: Doküman oluşturuldu ve kaydedildi: " + cleanFileName + " (" + fileId + ") by " + creatorUserId);

                // Disk'teki dosya yolunu da logla
                String diskFileName = document.getDiskFileName();
                String fullPath = Protocol.DOCUMENTS_FOLDER + diskFileName;
                Protocol.log("DEBUG: Disk dosya yolu: " + fullPath);

            } else {
                Protocol.log("WARNING: Doküman oluşturuldu ama kaydedilemedi: " + cleanFileName + " (" + fileId + ")");
            }

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
     * 🔧 UPDATED: loadDocumentFromDisk metodunu yeni dosya formatına göre güncelle
     */
    private Document loadDocumentFromDisk(String fileId) {
        Protocol.log("=== LOAD DOCUMENT FROM DISK DEBUG (UPDATED FORMAT) ===");
        Protocol.log("DEBUG: fileId: '" + fileId + "'");

        try {
            // FileID'yi temizle
            String cleanFileId = Utils.sanitizeFileName(fileId);
            if (cleanFileId == null || cleanFileId.isEmpty()) {
                Protocol.log("ERROR: Geçersiz fileId: " + fileId);
                return null;
            }

            Protocol.log("DEBUG: cleanFileId: '" + cleanFileId + "'");

            // 🔧 UPDATED: Yeni formatda dosya arama - fileName - fileId.txt pattern'i
            String documentsPath = Protocol.DOCUMENTS_FOLDER;
            Protocol.log("DEBUG: Documents klasörü: " + documentsPath);

            // Documents klasöründeki tüm dosyaları tara
            Path documentsDir = Paths.get(documentsPath);
            if (!Files.exists(documentsDir)) {
                Protocol.log("ERROR: Documents klasörü bulunamadı: " + documentsPath);
                return null;
            }

            // fileId ile biten dosyaları ara
            String targetPattern = " - " + cleanFileId + ".txt";
            Protocol.log("DEBUG: Aranan pattern: *" + targetPattern);

            Path matchingFile = null;
            String matchingFileName = null;

            try {
                matchingFile = Files.list(documentsDir)
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().endsWith(targetPattern))
                        .findFirst()
                        .orElse(null);

                if (matchingFile != null) {
                    matchingFileName = matchingFile.getFileName().toString();
                    Protocol.log("DEBUG: Eşleşen dosya bulundu: " + matchingFileName);
                } else {
                    Protocol.log("DEBUG: Pattern ile eşleşen dosya bulunamadı");
                }

            } catch (IOException e) {
                Protocol.log("ERROR: Dosya tarama hatası: " + e.getMessage());
            }

            // Eşleşen dosya bulunamadıysa test dosyası oluştur
            if (matchingFile == null) {
                Protocol.log("DEBUG: Dosya bulunamadı, test dosyası oluşturuluyor...");
                createTestFileIfNeeded(cleanFileId);

                // Tekrar ara
                try {
                    matchingFile = Files.list(documentsDir)
                            .filter(Files::isRegularFile)
                            .filter(path -> path.getFileName().toString().endsWith(targetPattern))
                            .findFirst()
                            .orElse(null);

                    if (matchingFile != null) {
                        matchingFileName = matchingFile.getFileName().toString();
                        Protocol.log("DEBUG: Test dosyası sonrası bulundu: " + matchingFileName);
                    }
                } catch (IOException e) {
                    Protocol.log("ERROR: Test dosyası sonrası tarama hatası: " + e.getMessage());
                }
            }

            // Hala bulunamadıysa hata
            if (matchingFile == null) {
                Protocol.log("ERROR: Dosya hala bulunamadı: " + cleanFileId);
                return null;
            }

            String filePath = matchingFile.toString();
            Protocol.log("DEBUG: Dosya yolu: " + filePath);

            // Dosya bilgileri
            boolean javaExists = Files.exists(matchingFile);
            boolean javaReadable = Files.isReadable(matchingFile);
            boolean javaRegular = Files.isRegularFile(matchingFile);

            Protocol.log("DEBUG: Files.exists(): " + javaExists);
            Protocol.log("DEBUG: Files.isReadable(): " + javaReadable);
            Protocol.log("DEBUG: Files.isRegularFile(): " + javaRegular);

            if (javaExists) {
                try {
                    long size = Files.size(matchingFile);
                    Protocol.log("DEBUG: Dosya boyutu: " + size + " bytes");
                } catch (Exception e) {
                    Protocol.log("DEBUG: Dosya boyutu alınamadı: " + e.getMessage());
                }
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

            // 🔧 UPDATED: Original fileName'i dosya adından çıkar
            String originalFileName = extractOriginalFileName(matchingFileName, cleanFileId);
            Protocol.log("DEBUG: Çıkarılan original fileName: " + originalFileName);

            // Document oluştur
            Document doc = new Document(cleanFileId, originalFileName, "system");
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
     * 🔧 NEW: Dosya adından original fileName'i çıkar
     */
    private String extractOriginalFileName(String diskFileName, String fileId) {
        // diskFileName: "My Document - file_123456.txt"
        // fileId: "file_123456"
        // return: "My Document"

        String pattern = " - " + fileId + ".txt";
        if (diskFileName.endsWith(pattern)) {
            return diskFileName.substring(0, diskFileName.length() - pattern.length());
        }

        // Fallback: dosya adını olduğu gibi döndür
        return diskFileName;
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
        Protocol.log("=== INSERT TEXT DEBUG ===");
        Protocol.log("DEBUG: insertText - fileId: " + fileId + ", position: " + position +
                ", text: '" + text + "', userId: " + userId);

        // 🔧 SPACE CHARACTER DEBUG
        if (text != null) {
            Protocol.log("DEBUG: Text length: " + text.length());
            Protocol.log("DEBUG: Text isEmpty(): " + text.isEmpty());
            if (text.equals(" ")) {
                Protocol.log("DEBUG: *** SPACE CHARACTER DETECTED ***");
            }
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                Protocol.log("DEBUG: Char[" + i + "]: '" + c + "' (ASCII: " + (int)c + ")");
            }
        }

        Document doc = documents.get(fileId);

        // 🔧 VALIDATION DÜZELTMESİ - SPACE KARAKTERİ İÇİN
        if (doc == null) {
            Protocol.log("ERROR: Document not found for fileId: " + fileId);
            return false;
        }

        if (text == null) {
            Protocol.log("ERROR: Text is null");
            return false;
        }

        // ❌ ESKİ: text.isEmpty() space karakterini reddediyordu
        // ✅ YENİ: Sadece gerçekten boş stringi reddet
        if (text.length() == 0) {
            Protocol.log("ERROR: Text is empty (length = 0)");
            return false;
        }

        // Space karakteri için özel log
        if (text.equals(" ")) {
            Protocol.log("SUCCESS: Space character validation passed");
        }

        // Position validation ve auto-fix
        String currentContent = doc.getContent();
        Protocol.log("DEBUG: Current content length: " + currentContent.length());
        Protocol.log("DEBUG: Original position: " + position);

        if (position < 0) position = 0;
        if (position > currentContent.length()) position = currentContent.length();

        Protocol.log("DEBUG: Fixed position: " + position);

        // Yeni operasyon oluştur
        OperationalTransform.Operation newOp = OperationalTransform.createInsert(position, text, userId);
        Protocol.log("DEBUG: Created operation: " + newOp);

        // Son operasyonlara karşı transform et
        List<OperationalTransform.Operation> recentOps = doc.getRecentOperations(20);
        Protocol.log("DEBUG: Recent operations count: " + recentOps.size());

        List<OperationalTransform.Operation> transformedOps =
                OperationalTransform.transformOperationList(newOp, recentOps);
        Protocol.log("DEBUG: Transformed operations count: " + transformedOps.size());

        // Transform edilmiş operasyonları uygula
        boolean success = false;
        for (OperationalTransform.Operation transformedOp : transformedOps) {
            Protocol.log("DEBUG: Checking transformed op: " + transformedOp);

            // 🔧 VALIDATION İÇİN ÖZEL SPACE DEBUG
            boolean isValid = OperationalTransform.isValidOperation(transformedOp, doc.getContent());
            Protocol.log("DEBUG: isValidOperation: " + isValid);

            if (!isValid && transformedOp.content != null && transformedOp.content.equals(" ")) {
                Protocol.log("WARNING: Space character operation marked as invalid!");
                Protocol.log("DEBUG: Operation details - pos: " + transformedOp.position +
                        ", content: '" + transformedOp.content + "'" +
                        ", doc length: " + doc.getContent().length());
            }

            if (isValid) {
                String oldContent = doc.getContent();
                String newContent = OperationalTransform.applyOperation(doc.getContent(), transformedOp);
                doc.setContent(newContent);
                doc.addOperation(transformedOp);
                success = true;

                Protocol.log(String.format("SUCCESS: Insert applied - %s pos:%d->%d text:'%s'",
                        fileId, position, transformedOp.position, transformedOp.content));
                Protocol.log("DEBUG: Content changed from length " + oldContent.length() +
                        " to " + newContent.length());

                // Space character için özel success log
                if (transformedOp.content.equals(" ")) {
                    Protocol.log("🎉 SUCCESS: SPACE CHARACTER SUCCESSFULLY INSERTED! 🎉");
                }
            } else {
                Protocol.log("WARNING: Invalid transformed operation: " + transformedOp);

                // Space karakteri için özel invalid debug
                if (transformedOp.content != null && transformedOp.content.equals(" ")) {
                    Protocol.log("💥 ERROR: SPACE CHARACTER OPERATION IS INVALID! 💥");
                    Protocol.log("DEBUG: Space op pos: " + transformedOp.position);
                    Protocol.log("DEBUG: Doc length: " + doc.getContent().length());
                    Protocol.log("DEBUG: Position > length: " + (transformedOp.position > doc.getContent().length()));
                }
            }
        }

        Protocol.log("DEBUG: Final success: " + success);
        Protocol.log("========================");
        return success;
    }

    /**
     * Text delete - Robust OT ile çakışma çözümü - DÜZELTME
     */
    public synchronized boolean deleteText(String fileId, int position, int length, String userId) {
        Protocol.log("=== ENHANCED DELETE TEXT DEBUG ===");
        Protocol.log("DEBUG: deleteText - fileId: " + fileId + ", position: " + position +
                ", length: " + length + ", userId: " + userId);

        Document doc = documents.get(fileId);
        if (doc == null || length <= 0) {
            Protocol.log("ERROR: Document null or invalid length");
            return false;
        }

        // Current content analysis
        String currentContent = doc.getContent();
        int originalPosition = position;
        int originalLength = length;

        Protocol.log("DEBUG: Current content length: " + currentContent.length());
        Protocol.log("DEBUG: Original delete - pos: " + originalPosition + ", len: " + originalLength);

        // 🔧 STRICT POSITION AND LENGTH CLAMPING
        if (currentContent.length() == 0) {
            Protocol.log("ERROR: No content to delete");
            return false;
        }

        int clampedPosition = Math.max(0, Math.min(position, currentContent.length() - 1));
        int maxLength = Math.max(0, currentContent.length() - clampedPosition);
        int clampedLength = Math.max(1, Math.min(length, maxLength));

        if (maxLength <= 0) {
            Protocol.log("ERROR: No content available to delete at position " + position);
            return false;
        }

        Protocol.log("DEBUG: Clamped delete - pos: " + clampedPosition + ", len: " + clampedLength);

        // Yeni operasyon oluştur with clamped values
        OperationalTransform.Operation newOp = OperationalTransform.createDelete(clampedPosition, clampedLength, userId);
        Protocol.log("DEBUG: Created operation: " + newOp);

        // 🔧 REDUCED TRANSFORM SCOPE for stability
        List<OperationalTransform.Operation> recentOps = doc.getRecentOperations(3); // Significantly reduced
        Protocol.log("DEBUG: Recent operations count: " + recentOps.size());

        List<OperationalTransform.Operation> transformedOps =
                OperationalTransform.transformOperationList(newOp, recentOps);
        Protocol.log("DEBUG: Transformed operations count: " + transformedOps.size());

        // Transform edilmiş operasyonları uygula
        boolean success = false;
        for (OperationalTransform.Operation transformedOp : transformedOps) {
            Protocol.log("DEBUG: Checking transformed op: " + transformedOp);

            // 🔧 CONTENT-AWARE VALIDATION with auto-fix
            String currentState = doc.getContent();
            int currentStateLength = currentState.length();

            Protocol.log("DEBUG: Content state - length: " + currentStateLength +
                    ", op position: " + transformedOp.position +
                    ", op length: " + transformedOp.length);

            // 🔧 AUTO-FIX TRANSFORMED OPERATION if out of bounds
            OperationalTransform.Operation finalOp = transformedOp;

            if (transformedOp.position >= currentStateLength) {
                // Position completely out of bounds - move to safe position
                int newPos = Math.max(0, currentStateLength - 1);
                finalOp = transformedOp.withPosition(newPos);
                Protocol.log("DEBUG: Position auto-fixed: " + transformedOp.position + " → " + newPos);
            }

            if (finalOp.position + finalOp.length > currentStateLength) {
                // Length extends beyond content - trim length
                int newLength = Math.max(1, currentStateLength - finalOp.position);
                finalOp = finalOp.withLength(newLength);
                Protocol.log("DEBUG: Length auto-fixed: " + transformedOp.length + " → " + newLength);
            }

            // Final validation
            boolean isValid = OperationalTransform.isValidOperation(finalOp, currentState);
            Protocol.log("DEBUG: isValidOperation: " + isValid + " (after auto-fix)");

            if (isValid && finalOp.length > 0) {
                String oldContent = doc.getContent();
                String newContent = OperationalTransform.applyOperation(oldContent, finalOp);
                doc.setContent(newContent);
                doc.addOperation(finalOp);
                success = true;

                Protocol.log(String.format("SUCCESS: Delete applied - %s original pos:%d→%d len:%d→%d (fixed pos:%d len:%d)",
                        fileId, originalPosition, transformedOp.position, originalLength, transformedOp.length,
                        finalOp.position, finalOp.length));
                Protocol.log("DEBUG: Content changed from length " + oldContent.length() +
                        " to " + newContent.length());

            } else {
                Protocol.log("WARNING: Could not apply delete operation even after auto-fix");
            }
        }

        Protocol.log("DEBUG: Final success: " + success);
        Protocol.log("=== ENHANCED DELETE END ===");
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
     * 🔧 UPDATED: Dokümanı kaydet - fileName - fileId.txt formatında
     */
    public boolean saveDocument(String fileId) {
        Document doc = documents.get(fileId);
        if (doc == null) {
            Protocol.log("ERROR: Document not found for fileId: " + fileId);
            return false;
        }

        try {
            // 🔧 NEW FORMAT: fileName - fileId.txt
            String fileName = doc.getFileName();
            String diskFileName = fileName + " - " + fileId + ".txt";
            String filePath = Protocol.DOCUMENTS_FOLDER + diskFileName;

            Protocol.log("DEBUG: Saving document - fileId: " + fileId);
            Protocol.log("DEBUG: Original fileName: " + fileName);
            Protocol.log("DEBUG: Disk fileName: " + diskFileName);
            Protocol.log("DEBUG: Full path: " + filePath);

            boolean success = Utils.writeFileContent(filePath, doc.getContent());

            if (success) {
                doc.markSaved();
                Protocol.log("SUCCESS: Doküman kaydedildi: " + diskFileName + " (" + doc.getContent().length() + " karakter)");
            } else {
                Protocol.log("ERROR: Dosya yazma başarısız: " + filePath);
            }

            return success;

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
     * 🔧 UPDATED: Test dosyası oluşturma - yeni formatla
     */
    public void createTestFileIfNeeded(String fileId) {
        try {
            Protocol.log("DEBUG: Test dosyası kontrol ediliyor - fileId: " + fileId);

            // 🔧 NEW FORMAT: Test Document - fileId.txt
            String testFileName = "Test Document";
            String diskFileName = testFileName + " - " + fileId + ".txt";
            String filePath = Protocol.DOCUMENTS_FOLDER + diskFileName;
            Path path = Paths.get(filePath);

            Protocol.log("DEBUG: Test dosya yolu: " + filePath);

            if (!Files.exists(path)) {
                // Test içeriği oluştur
                String testContent = "Bu bir test dosyasıdır.\n" +
                        "Dosya Adı: " + testFileName + "\n" +
                        "Dosya ID: " + fileId + "\n" +
                        "Oluşturulma tarihi: " + new java.util.Date() + "\n" +
                        "\nBu dosyayı düzenleyebilirsiniz.";

                // Klasörü oluştur
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }

                // Dosyayı oluştur
                Files.write(path, testContent.getBytes("UTF-8"));

                Protocol.log("SUCCESS: Test dosyası oluşturuldu: " + diskFileName);
                Protocol.log("DEBUG: Dosya boyutu: " + Files.size(path) + " bytes");
            } else {
                Protocol.log("DEBUG: Test dosyası zaten mevcut: " + diskFileName);
                Protocol.log("DEBUG: Mevcut dosya boyutu: " + Files.size(path) + " bytes");
            }

        } catch (Exception e) {
            Protocol.logError("DEBUG: Test dosyası oluşturma hatası: " + fileId, e);
        }
    }

    /**
     * 🔧 UPDATED: Tüm dokümanları listele - yeni dosya formatına uygun
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

        // 🔧 UPDATED: Diskdeki dokümanlar - yeni format (fileName - fileId.txt)
        try {
            Protocol.log("DEBUG: Disk dosyaları taranıyor (yeni format)...");
            Files.list(Paths.get(Protocol.DOCUMENTS_FOLDER))
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".txt"))
                    .filter(path -> path.getFileName().toString().contains(" - file_")) // New format pattern
                    .forEach(path -> {
                        String diskFileName = path.getFileName().toString();
                        Protocol.log("DEBUG: Disk dosyası bulundu: " + diskFileName);

                        // fileName - fileId.txt formatından fileId'yi çıkar
                        String fileId = extractFileIdFromDiskName(diskFileName);
                        String fileName = extractFileNameFromDiskName(diskFileName, fileId);

                        Protocol.log("DEBUG: Extracted - fileId: '" + fileId + "', fileName: '" + fileName + "'");

                        if (fileId != null && fileName != null && !documents.containsKey(fileId)) {
                            try {
                                long lastModified = Files.getLastModifiedTime(path).toMillis();
                                long fileSize = Files.size(path);

                                DocumentInfo info = new DocumentInfo(fileId, fileName, 0, lastModified, (int)fileSize);
                                result.add(info);
                                Protocol.log("DEBUG: Disk'ten listeye eklendi: " + fileId + " -> " + fileName);
                            } catch (IOException e) {
                                Protocol.logError("DEBUG: Dosya bilgisi okunamadı: " + diskFileName, e);
                            }
                        } else if (documents.containsKey(fileId)) {
                            Protocol.log("DEBUG: Dosya zaten memory'de var, skip: " + fileId);
                        } else {
                            Protocol.log("DEBUG: FileId veya fileName çıkarılamadı: " + diskFileName);
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

    /**
     * 🔧 NEW: Disk dosya adından fileId çıkar
     * "My Document - file_123456.txt" -> "file_123456"
     */
    private String extractFileIdFromDiskName(String diskFileName) {
        try {
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
            Protocol.log("DEBUG: FileId çıkarma hatası: " + diskFileName + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * 🔧 NEW: Disk dosya adından fileName çıkar
     * "My Document - file_123456.txt" -> "My Document"
     */
    private String extractFileNameFromDiskName(String diskFileName, String fileId) {
        try {
            if (fileId == null) return null;

            // Pattern: " - fileId.txt"
            String pattern = " - " + fileId + ".txt";

            if (diskFileName.endsWith(pattern)) {
                return diskFileName.substring(0, diskFileName.length() - pattern.length());
            }

            return null;

        } catch (Exception e) {
            Protocol.log("DEBUG: FileName çıkarma hatası: " + diskFileName + " - " + e.getMessage());
            return null;
        }
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

        public List<OperationalTransform.Operation> getRecentOperations(int maxCount) {
            // 🔧 FURTHER REDUCTION for stability
            int actualMax = Math.min(maxCount, 2); // Never more than 2 operations

            if (operationHistory.size() <= actualMax) {
                return new ArrayList<>(operationHistory);
            }

            // Return only the most recent operations
            List<OperationalTransform.Operation> recent = new ArrayList<>();
            int startIndex = operationHistory.size() - actualMax;

            for (int i = startIndex; i < operationHistory.size(); i++) {
                recent.add(operationHistory.get(i));
            }

            return recent;
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

        /**
         * 🔧 UPDATED: Disk dosya adı - yeni format
         */
        public String getDiskFileName() {
            return fileName + " - " + fileId + ".txt";
        }

        public String getDisplayInfo() {
            return "📄 " + fileName + " (Modified: " + new java.util.Date(lastModified) + ")";
        }
    }
}