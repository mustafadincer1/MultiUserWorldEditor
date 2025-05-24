package Server;

import Common.*;
import java.util.*;

/**
 * Operational Transform (OT) algoritmaları
 * Çok kullanıcılı eş zamanlı düzenleme için conflict resolution
 *
 * OT Prensipleri:
 * 1. Convergence: Tüm kullanıcılar aynı final duruma ulaşır
 * 2. Causality Preservation: Operasyon sırası korunur
 * 3. Intention Preservation: Kullanıcının niyeti korunur
 */
public class OperationalTransform {

    /**
     * Text operasyon tipleri
     */
    public enum OperationType {
        INSERT,    // Metin ekleme
        DELETE,    // Metin silme
        RETAIN     // Pozisyonu koruma (gelişmiş OT için)
    }

    /**
     * Operasyon sınıfı - immutable
     */
    public static class Operation {
        public final OperationType type;
        public final int position;
        public final String text;
        public final int length;
        public final String userId;
        public final long timestamp;
        public final int siteId; // Lamport timestamp için

        // INSERT operasyonu için
        public Operation(OperationType type, int position, String text, String userId, long timestamp) {
            this.type = type;
            this.position = position;
            this.text = text != null ? text : "";
            this.length = this.text.length();
            this.userId = userId;
            this.timestamp = timestamp;
            this.siteId = userId.hashCode(); // Basit site ID
        }

        // DELETE operasyonu için
        public Operation(OperationType type, int position, int length, String userId, long timestamp) {
            this.type = type;
            this.position = position;
            this.text = "";
            this.length = length;
            this.userId = userId;
            this.timestamp = timestamp;
            this.siteId = userId.hashCode();
        }

        // RETAIN operasyonu için
        public Operation(int retainLength, String userId, long timestamp) {
            this.type = OperationType.RETAIN;
            this.position = 0;
            this.text = "";
            this.length = retainLength;
            this.userId = userId;
            this.timestamp = timestamp;
            this.siteId = userId.hashCode();
        }

        @Override
        public String toString() {
            switch (type) {
                case INSERT:
                    return String.format("INSERT(%d, \"%s\")", position, text);
                case DELETE:
                    return String.format("DELETE(%d, %d)", position, length);
                case RETAIN:
                    return String.format("RETAIN(%d)", length);
                default:
                    return "UNKNOWN";
            }
        }

        public Operation copy() {
            if (type == OperationType.DELETE) {
                return new Operation(type, position, length, userId, timestamp);
            } else {
                return new Operation(type, position, text, userId, timestamp);
            }
        }
    }

    /**
     * İki operasyonu transform eder (ana OT algoritması)
     *
     * @param op1 İlk operasyon (client'tan gelen)
     * @param op2 İkinci operasyon (server'da mevcut)
     * @param op1HasPriority op1'in priority'si var mı (tie-breaking için)
     * @return Transform edilmiş op1
     */
    public static Operation transform(Operation op1, Operation op2, boolean op1HasPriority) {
        if (op1 == null || op2 == null) {
            return op1;
        }

        // Aynı kullanıcının operasyonları transform edilmez
        if (op1.userId.equals(op2.userId)) {
            return op1;
        }

        Utils.log(String.format("Transforming: %s against %s", op1, op2));

        // Operasyon tiplerine göre transform et
        switch (op1.type) {
            case INSERT:
                return transformInsert(op1, op2, op1HasPriority);
            case DELETE:
                return transformDelete(op1, op2, op1HasPriority);
            case RETAIN:
                return transformRetain(op1, op2);
            default:
                return op1;
        }
    }

    /**
     * INSERT operasyonunu transform et
     */
    private static Operation transformInsert(Operation insertOp, Operation otherOp, boolean insertHasPriority) {
        switch (otherOp.type) {
            case INSERT:
                return transformInsertAgainstInsert(insertOp, otherOp, insertHasPriority);
            case DELETE:
                return transformInsertAgainstDelete(insertOp, otherOp);
            case RETAIN:
                return insertOp; // RETAIN operasyonu INSERT'i etkilemez
            default:
                return insertOp;
        }
    }

    /**
     * INSERT vs INSERT transform
     */
    private static Operation transformInsertAgainstInsert(Operation op1, Operation op2, boolean op1HasPriority) {
        if (op2.position < op1.position) {
            // op2, op1'den önce - op1'in pozisyonunu kaydır
            int newPosition = op1.position + op2.length;
            return new Operation(op1.type, newPosition, op1.text, op1.userId, op1.timestamp);

        } else if (op2.position > op1.position) {
            // op2, op1'den sonra - değişiklik yok
            return op1;

        } else {
            // Aynı pozisyon - tie-breaking
            if (op1HasPriority || op1.siteId < op2.siteId) {
                // op1 önce kalır
                return op1;
            } else {
                // op1, op2'den sonraya kaydırılır
                int newPosition = op1.position + op2.length;
                return new Operation(op1.type, newPosition, op1.text, op1.userId, op1.timestamp);
            }
        }
    }

    /**
     * INSERT vs DELETE transform
     */
    private static Operation transformInsertAgainstDelete(Operation insertOp, Operation deleteOp) {
        int deleteEnd = deleteOp.position + deleteOp.length;

        if (insertOp.position <= deleteOp.position) {
            // Insert, delete'den önce - delete pozisyonu kaydırılır (ama bu delete transform değil)
            return insertOp;

        } else if (insertOp.position >= deleteEnd) {
            // Insert, delete'den sonra - insert pozisyonunu geri kaydır
            int newPosition = insertOp.position - deleteOp.length;
            return new Operation(insertOp.type, newPosition, insertOp.text, insertOp.userId, insertOp.timestamp);

        } else {
            // Insert, delete aralığında - delete başlangıcına kaydır
            return new Operation(insertOp.type, deleteOp.position, insertOp.text, insertOp.userId, insertOp.timestamp);
        }
    }

    /**
     * DELETE operasyonunu transform et
     */
    private static Operation transformDelete(Operation deleteOp, Operation otherOp, boolean deleteHasPriority) {
        switch (otherOp.type) {
            case INSERT:
                return transformDeleteAgainstInsert(deleteOp, otherOp);
            case DELETE:
                return transformDeleteAgainstDelete(deleteOp, otherOp);
            case RETAIN:
                return deleteOp; // RETAIN operasyonu DELETE'i etkilemez
            default:
                return deleteOp;
        }
    }

    /**
     * DELETE vs INSERT transform
     */
    private static Operation transformDeleteAgainstInsert(Operation deleteOp, Operation insertOp) {
        if (insertOp.position <= deleteOp.position) {
            // Insert, delete'den önce - delete pozisyonunu kaydır
            int newPosition = deleteOp.position + insertOp.length;
            return new Operation(deleteOp.type, newPosition, deleteOp.length, deleteOp.userId, deleteOp.timestamp);

        } else if (insertOp.position >= deleteOp.position + deleteOp.length) {
            // Insert, delete'den sonra - değişiklik yok
            return deleteOp;

        } else {
            // Insert, delete aralığında - delete'i böl
            // Basit yaklaşım: delete uzunluğunu artır
            int newLength = deleteOp.length + insertOp.length;
            return new Operation(deleteOp.type, deleteOp.position, newLength, deleteOp.userId, deleteOp.timestamp);
        }
    }

    /**
     * DELETE vs DELETE transform
     */
    private static Operation transformDeleteAgainstDelete(Operation op1, Operation op2) {
        int op1End = op1.position + op1.length;
        int op2End = op2.position + op2.length;

        if (op2End <= op1.position) {
            // op2, op1'den tamamen önce - op1 pozisyonunu geri kaydır
            int newPosition = op1.position - op2.length;
            return new Operation(op1.type, newPosition, op1.length, op1.userId, op1.timestamp);

        } else if (op2.position >= op1End) {
            // op2, op1'den tamamen sonra - değişiklik yok
            return op1;

        } else {
            // Çakışma var - karmaşık durum
            return handleDeleteDeleteOverlap(op1, op2);
        }
    }

    /**
     * DELETE vs DELETE çakışması
     */
    private static Operation handleDeleteDeleteOverlap(Operation op1, Operation op2) {
        int op1End = op1.position + op1.length;
        int op2End = op2.position + op2.length;

        // Çakışan alanı hesapla
        int overlapStart = Math.max(op1.position, op2.position);
        int overlapEnd = Math.min(op1End, op2End);
        int overlapLength = Math.max(0, overlapEnd - overlapStart);

        if (overlapLength >= op1.length) {
            // op1 tamamen op2 içinde - op1'i geçersiz kıl
            return new Operation(op1.type, op1.position, 0, op1.userId, op1.timestamp);
        }

        // Kısmi çakışma - op1'in pozisyonunu ve uzunluğunu ayarla
        int newPosition = Math.min(op1.position, op2.position);
        int newLength = op1.length - overlapLength;

        if (op1.position < op2.position) {
            // op1 başlıyor, op2 çakışıyor
            return new Operation(op1.type, newPosition, newLength, op1.userId, op1.timestamp);
        } else {
            // op2 başlıyor, op1 çakışıyor - op1'i kaydır
            return new Operation(op1.type, op2.position, newLength, op1.userId, op1.timestamp);
        }
    }

    /**
     * RETAIN operasyonunu transform et
     */
    private static Operation transformRetain(Operation retainOp, Operation otherOp) {
        // RETAIN operasyonları basit durumda etkilenmez
        return retainOp;
    }

    /**
     * Operasyon listesini transform et
     */
    public static List<Operation> transformOperationList(List<Operation> clientOps, List<Operation> serverOps) {
        List<Operation> transformedOps = new ArrayList<>();

        for (Operation clientOp : clientOps) {
            Operation transformedOp = clientOp;

            // Her server operasyonuna karşı transform et
            for (Operation serverOp : serverOps) {
                transformedOp = transform(transformedOp, serverOp, false);
            }

            transformedOps.add(transformedOp);
        }

        return transformedOps;
    }

    /**
     * Operasyonu string'e uygula
     */
    public static String applyOperation(String text, Operation operation) {
        if (text == null) text = "";

        try {
            switch (operation.type) {
                case INSERT:
                    if (operation.position < 0 || operation.position > text.length()) {
                        Utils.log("Geçersiz INSERT pozisyonu: " + operation.position + " text length: " + text.length());
                        return text;
                    }
                    return text.substring(0, operation.position) +
                            operation.text +
                            text.substring(operation.position);

                case DELETE:
                    if (operation.position < 0 || operation.position + operation.length > text.length()) {
                        Utils.log("Geçersiz DELETE pozisyonu: " + operation.position + " length: " + operation.length + " text length: " + text.length());
                        return text;
                    }
                    return text.substring(0, operation.position) +
                            text.substring(operation.position + operation.length);

                case RETAIN:
                    return text; // RETAIN sadece pozisyon tutar, text değiştirmez

                default:
                    return text;
            }
        } catch (Exception e) {
            Utils.logError("Operasyon uygulama hatası: " + operation, e);
            return text;
        }
    }

    /**
     * Operasyon listesini sırayla uygula
     */
    public static String applyOperations(String text, List<Operation> operations) {
        String result = text;

        for (Operation op : operations) {
            result = applyOperation(result, op);
        }

        return result;
    }

    /**
     * Operasyon geçerlilik kontrolü
     */
    public static boolean isValidOperation(Operation operation, String currentText) {
        if (operation == null || currentText == null) {
            return false;
        }

        switch (operation.type) {
            case INSERT:
                return operation.position >= 0 &&
                        operation.position <= currentText.length() &&
                        operation.text != null;

            case DELETE:
                return operation.position >= 0 &&
                        operation.position + operation.length <= currentText.length() &&
                        operation.length > 0;

            case RETAIN:
                return operation.length >= 0;

            default:
                return false;
        }
    }

    /**
     * Operasyon öncelik karşılaştırması (Lamport timestamp)
     */
    public static boolean hasHigherPriority(Operation op1, Operation op2) {
        if (op1.timestamp != op2.timestamp) {
            return op1.timestamp < op2.timestamp; // Daha erken timestamp öncelikli
        }

        // Aynı timestamp - site ID ile karşılaştır
        return op1.siteId < op2.siteId;
    }

    /**
     * Debug için operasyon history'yi yazdır
     */
    public static void printOperationHistory(List<Operation> operations) {
        Utils.log("=== OPERATION HISTORY ===");
        for (int i = 0; i < operations.size(); i++) {
            Operation op = operations.get(i);
            Utils.log(String.format("%d: %s by %s at %d",
                    i, op.toString(), op.userId, op.timestamp));
        }
        Utils.log("========================");
    }

    /**
     * İki string'in farkını operasyon olarak hesapla (basit diff algoritması)
     */
    public static List<Operation> computeDifference(String oldText, String newText, String userId) {
        List<Operation> operations = new ArrayList<>();
        long timestamp = System.currentTimeMillis();

        // Basit diff algoritması - baştan ve sondan karşılaştır
        int commonPrefixLength = 0;
        int minLength = Math.min(oldText.length(), newText.length());

        // Ortak prefix bul
        while (commonPrefixLength < minLength &&
                oldText.charAt(commonPrefixLength) == newText.charAt(commonPrefixLength)) {
            commonPrefixLength++;
        }

        // Ortak suffix bul
        int commonSuffixLength = 0;
        int oldSuffixStart = oldText.length() - 1;
        int newSuffixStart = newText.length() - 1;

        while (commonSuffixLength < minLength - commonPrefixLength &&
                oldSuffixStart >= commonPrefixLength && newSuffixStart >= commonPrefixLength &&
                oldText.charAt(oldSuffixStart) == newText.charAt(newSuffixStart)) {
            commonSuffixLength++;
            oldSuffixStart--;
            newSuffixStart--;
        }

        // Değişen kısım
        String deletedPart = oldText.substring(commonPrefixLength, oldText.length() - commonSuffixLength);
        String insertedPart = newText.substring(commonPrefixLength, newText.length() - commonSuffixLength);

        // Önce silme, sonra ekleme operasyonları
        if (!deletedPart.isEmpty()) {
            operations.add(new Operation(OperationType.DELETE, commonPrefixLength, deletedPart.length(), userId, timestamp));
        }

        if (!insertedPart.isEmpty()) {
            operations.add(new Operation(OperationType.INSERT, commonPrefixLength, insertedPart, userId, timestamp + 1));
        }

        return operations;
    }
}
