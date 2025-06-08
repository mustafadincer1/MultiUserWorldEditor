package Common;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Robust Operational Transform İmplementasyonu
 * 3+ kullanıcı, network gecikme ve kompleks çakışmaları handle eder
 *
 * Temel Prensipler:
 * 1. Convergence: Tüm kullanıcılar aynı final duruma ulaşır
 * 2. Causality: Operasyon sırası korunur
 * 3. Intention: Kullanıcının amacı korunur
 */
public class OperationalTransform {

    // Global operasyon sayacı (Lamport clock için)
    private static final AtomicLong globalClock = new AtomicLong(0);

    /**
     * Text operasyon sınıfı - immutable ve thread-safe
     */
    public static class Operation {
        public enum Type { INSERT, DELETE }

        public final Type type;
        public final int position;
        public final String content;
        public final int length;
        public final String userId;
        public final long timestamp;        // Gerçek zaman
        public final long logicalClock;     // Lamport logical clock
        public final int siteId;            // Site priority için

        // INSERT constructor
        public Operation(Type type, int position, String content, String userId) {
            this.type = type;
            this.position = position;
            this.content = content != null ? content : "";
            this.length = this.content.length();
            this.userId = userId;
            this.timestamp = System.currentTimeMillis();
            this.logicalClock = globalClock.incrementAndGet();
            this.siteId = userId.hashCode();
        }

        // DELETE constructor
        public Operation(Type type, int position, int length, String userId) {
            this.type = type;
            this.position = position;
            this.content = "";
            this.length = length;
            this.userId = userId;
            this.timestamp = System.currentTimeMillis();
            this.logicalClock = globalClock.incrementAndGet();
            this.siteId = userId.hashCode();
        }

        // Copy constructor (transform için)
        private Operation(Operation original, int newPosition, String newContent, int newLength) {
            this.type = original.type;
            this.position = newPosition;
            this.content = newContent;
            this.length = newLength;
            this.userId = original.userId;
            this.timestamp = original.timestamp;
            this.logicalClock = original.logicalClock;
            this.siteId = original.siteId;
        }

        public Operation withPosition(int newPosition) {
            return new Operation(this, newPosition, this.content, this.length);
        }

        public Operation withLength(int newLength) {
            return new Operation(this, this.position, this.content, newLength);
        }

        public boolean isInsert() { return type == Type.INSERT; }
        public boolean isDelete() { return type == Type.DELETE; }

        @Override
        public String toString() {
            return String.format("%s(%d,%s) by %s@%d",
                    type, position, isInsert() ? "\"" + content + "\"" : length, userId, logicalClock);
        }
    }

    /**
     * Ana transform metodu - iki operasyonu birbirine göre transform eder
     *
     * @param clientOp Client'tan gelen operasyon
     * @param serverOp Server'da bulunan operasyon
     * @return Transform edilmiş client operasyonu
     */
    public static Operation transform(Operation clientOp, Operation serverOp) {
        if (clientOp == null || serverOp == null) {
            return clientOp;
        }

        // Aynı kullanıcının operasyonları transform edilmez
        if (clientOp.userId.equals(serverOp.userId)) {
            return clientOp;
        }

        Protocol.log(String.format("Transforming %s against %s", clientOp, serverOp));

        if (clientOp.isInsert() && serverOp.isInsert()) {
            return transformInsertInsert(clientOp, serverOp);
        } else if (clientOp.isInsert() && serverOp.isDelete()) {
            return transformInsertDelete(clientOp, serverOp);
        } else if (clientOp.isDelete() && serverOp.isInsert()) {
            return transformDeleteInsert(clientOp, serverOp);
        } else if (clientOp.isDelete() && serverOp.isDelete()) {
            return transformDeleteDelete(clientOp, serverOp);
        }

        return clientOp;
    }

    /**
     * INSERT vs INSERT transform
     * Pozisyon çakışmalarını Lamport clock ile çözer
     */
    private static Operation transformInsertInsert(Operation op1, Operation op2) {
        if (op2.position < op1.position) {
            // op2 daha önce - op1'i kaydır
            return op1.withPosition(op1.position + op2.length);
        } else if (op2.position > op1.position) {
            // op2 daha sonra - değişiklik yok
            return op1;
        } else {
            // Aynı pozisyon - logical clock ile karar ver
            if (hasHigherPriority(op2, op1)) {
                // op2 öncelikli - op1'i kaydır
                return op1.withPosition(op1.position + op2.length);
            } else {
                // op1 öncelikli - değişiklik yok
                return op1;
            }
        }
    }

    /**
     * INSERT vs DELETE transform
     */
    private static Operation transformInsertDelete(Operation insertOp, Operation deleteOp) {
        int deleteEnd = deleteOp.position + deleteOp.length;

        if (insertOp.position <= deleteOp.position) {
            // Insert, delete'den önce veya başlangıcında - değişiklik yok
            return insertOp;
        } else if (insertOp.position >= deleteEnd) {
            // Insert, delete'den sonra - pozisyonu geri kaydır
            return insertOp.withPosition(insertOp.position - deleteOp.length);
        } else {
            // Insert, delete aralığında - delete başına kaydır
            return insertOp.withPosition(deleteOp.position);
        }
    }

    /**
     * DELETE vs INSERT transform
     */
    private static Operation transformDeleteInsert(Operation deleteOp, Operation insertOp) {
        if (insertOp.position <= deleteOp.position) {
            // Insert, delete'den önce - delete'i kaydır
            return deleteOp.withPosition(deleteOp.position + insertOp.length);
        } else if (insertOp.position >= deleteOp.position + deleteOp.length) {
            // Insert, delete'den sonra - değişiklik yok
            return deleteOp;
        } else {
            // Insert, delete aralığında - delete'i böl
            // Basit yaklaşım: delete uzunluğunu artır
            return deleteOp.withLength(deleteOp.length + insertOp.length);
        }
    }

    /**
     * DELETE vs DELETE transform
     * En karmaşık durum - çakışan silme operasyonları
     */
    private static Operation transformDeleteDelete(Operation op1, Operation op2) {
        int op1End = op1.position + op1.length;
        int op2End = op2.position + op2.length;

        if (op2End <= op1.position) {
            // op2 tamamen op1'den önce - op1'i geri kaydır
            return op1.withPosition(op1.position - op2.length);
        } else if (op2.position >= op1End) {
            // op2 tamamen op1'den sonra - değişiklik yok
            return op1;
        } else {
            // Çakışma var - overlap hesapla
            return handleDeleteOverlap(op1, op2);
        }
    }

    /**
     * DELETE çakışmalarını handle eder
     */
    private static Operation handleDeleteOverlap(Operation op1, Operation op2) {
        int op1End = op1.position + op1.length;
        int op2End = op2.position + op2.length;

        // Çakışan aralığı hesapla
        int overlapStart = Math.max(op1.position, op2.position);
        int overlapEnd = Math.min(op1End, op2End);
        int overlapLength = Math.max(0, overlapEnd - overlapStart);

        if (overlapLength >= op1.length) {
            // op1 tamamen op2 içinde - op1'i geçersiz kıl
            return op1.withLength(0);
        }

        // Kısmi çakışma
        if (op1.position < op2.position) {
            // op1 başlıyor, op2 çakışıyor - op1'in uzunluğunu kısalt
            int newLength = op2.position - op1.position;
            return op1.withLength(newLength);
        } else {
            // op2 başlıyor, op1 çakışıyor - op1'i kaydır ve kısalt
            int newPosition = op2.position;
            int newLength = op1.length - overlapLength;
            return op1.withPosition(newPosition).withLength(Math.max(0, newLength));
        }
    }

    /**
     * Operasyon listesini sıralı şekilde transform eder
     * 3+ kullanıcı senaryosu için kritik
     */
    public static List<Operation> transformOperationList(Operation clientOp, List<Operation> serverOps) {
        List<Operation> result = new ArrayList<>();

        Operation transformedOp = clientOp;

        // Server operasyonlarını logical clock'a göre sırala
        List<Operation> sortedServerOps = new ArrayList<>(serverOps);
        sortedServerOps.sort((a, b) -> Long.compare(a.logicalClock, b.logicalClock));

        // Her server operasyonuna karşı transform et
        for (Operation serverOp : sortedServerOps) {
            transformedOp = transform(transformedOp, serverOp);

            // Geçersiz operasyon (length 0) ise skip et
            if (transformedOp.length == 0 && transformedOp.isDelete()) {
                Protocol.log("Operation invalidated during transform: " + clientOp);
                return result; // Boş liste döndür
            }
        }

        result.add(transformedOp);
        return result;
    }

    /**
     * Batch operasyon transform - network gecikme kompensasyonu
     * Client'tan gelen operasyon listesini server state'e karşı transform eder
     */
    public static List<Operation> transformBatch(List<Operation> clientOps, List<Operation> serverOps) {
        List<Operation> result = new ArrayList<>();

        // Client operasyonlarını logical clock'a göre sırala
        List<Operation> sortedClientOps = new ArrayList<>(clientOps);
        sortedClientOps.sort((a, b) -> Long.compare(a.logicalClock, b.logicalClock));

        for (Operation clientOp : sortedClientOps) {
            List<Operation> transformedOps = transformOperationList(clientOp, serverOps);
            result.addAll(transformedOps);

            // Transform edilmiş operasyonu server history'e ekle (sonraki transform'lar için)
            serverOps.addAll(transformedOps);
        }

        return result;
    }

    public static String applyOperation(String text, Operation op) {
        if (text == null) text = "";

        try {
            if (op.isInsert()) {
                // INSERT - position auto-fix
                int safePosition = Math.max(0, Math.min(op.position, text.length()));

                if (safePosition != op.position) {
                    Protocol.log("DEBUG: INSERT position auto-fixed: " + op.position + " → " + safePosition);
                }

                return text.substring(0, safePosition) + op.content + text.substring(safePosition);

            } else {
                // DELETE - mevcut kontrol doğru
                if (op.position < 0 || op.position + op.length > text.length() || op.length <= 0) {
                    Protocol.log("Invalid DELETE: pos=" + op.position + " len=" + op.length + " text length=" + text.length());
                    return text;
                }
                return text.substring(0, op.position) + text.substring(op.position + op.length);
            }
        } catch (Exception e) {
            Protocol.logError("Operation apply error: " + op, e);
            return text;
        }
    }

    /**
     * Operasyon listesini sıralı uygula
     */
    public static String applyOperations(String text, List<Operation> operations) {
        String result = text;

        // Logical clock sırasına göre uygula
        List<Operation> sortedOps = new ArrayList<>(operations);
        sortedOps.sort((a, b) -> Long.compare(a.logicalClock, b.logicalClock));

        for (Operation op : sortedOps) {
            result = applyOperation(result, op);
        }

        return result;
    }

    /**
     * Operasyon prioritesi karşılaştırması (Lamport timestamp)
     */
    private static boolean hasHigherPriority(Operation op1, Operation op2) {
        if (op1.logicalClock != op2.logicalClock) {
            return op1.logicalClock < op2.logicalClock;
        }

        // Aynı logical clock - site ID ile tie-break
        if (op1.siteId != op2.siteId) {
            return op1.siteId < op2.siteId;
        }

        // Son resort - timestamp
        return op1.timestamp < op2.timestamp;
    }

    /**
     * Operasyon validation - DÜZELTME
     */
    public static boolean isValidOperation(Operation op, String currentText) {
        if (op == null || currentText == null) {
            return false;
        }

        if (op.isInsert()) {
            // INSERT için pozisyon text uzunluğundan büyük olabilir (append işlemi)
            // Sadece negatif olmadığını kontrol et
            boolean valid = op.position >= 0 &&
                    op.content != null &&
                    op.length == op.content.length();

            // DEBUG için log ekle
            if (!valid) {
                Protocol.log("DEBUG: INSERT validation failed - pos: " + op.position +
                        ", content: " + (op.content != null ? "'" + op.content + "'" : "null") +
                        ", text length: " + currentText.length());
            }

            return valid;
        } else {
            // DELETE için mevcut kontrol doğru
            boolean valid = op.position >= 0 &&
                    op.position + op.length <= currentText.length() &&
                    op.length > 0;

            if (!valid) {
                Protocol.log("DEBUG: DELETE validation failed - pos: " + op.position +
                        ", length: " + op.length +
                        ", text length: " + currentText.length());
            }

            return valid;
        }
    }

    /**
     * Factory metotları
     */
    public static Operation createInsert(int position, String text, String userId) {
        return new Operation(Operation.Type.INSERT, position, text, userId);
    }

    public static Operation createDelete(int position, int length, String userId) {
        return new Operation(Operation.Type.DELETE, position, length, userId);
    }
}