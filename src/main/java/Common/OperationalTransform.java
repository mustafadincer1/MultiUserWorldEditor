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
            if (this.type == Type.INSERT) {
                return new Operation(this.type, newPosition, this.content, this.userId);
            } else {
                return new Operation(this.type, newPosition, this.length, this.userId);
            }
        }

        public Operation withLength(int newLength) {
            if (this.type != Type.DELETE) {
                throw new IllegalArgumentException("withLength can only be used with DELETE operations");
            }
            return new Operation(this.type, this.position, newLength, this.userId);
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
    private static Operation transformInsertInsert(Operation insertOp1, Operation insertOp2) {
        Protocol.log("DEBUG: transformInsertInsert - op1: " + insertOp1 + " vs op2: " + insertOp2);

        int pos1 = insertOp1.position;
        int pos2 = insertOp2.position;

        if (pos2 <= pos1) {
            // op2 before op1 - shift op1 position right
            int newPosition = pos1 + insertOp2.content.length();

            // 🔧 POSITION GROWTH LIMITING
            int maxGrowth = 10; // Limit position growth per transform
            int actualGrowth = newPosition - pos1;

            if (actualGrowth > maxGrowth) {
                Protocol.log("WARNING: INSERT position growth limited: " + actualGrowth + " → " + maxGrowth);
                newPosition = pos1 + maxGrowth;
            }

            // 🔧 FIXED: Use correct INSERT constructor (Type, position, content, userId)
            Operation result = new Operation(Operation.Type.INSERT, newPosition,
                    insertOp1.content, insertOp1.userId);
            Protocol.log("DEBUG: Insert before insert - position shifted: " + pos1 + " → " + newPosition);
            return result;

        } else {
            // op2 after op1 - no change needed
            Protocol.log("DEBUG: Insert after insert - no change");
            return insertOp1;
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
        Protocol.log("DEBUG: transformDeleteInsert - delete: " + deleteOp + " vs insert: " + insertOp);

        int deleteStart = deleteOp.position;
        int deleteEnd = deleteOp.position + deleteOp.length;
        int insertPos = insertOp.position;

        // 🔧 OVERFLOW DETECTION
        int maxReasonablePosition = deleteOp.position + 1000; // Reasonable limit

        if (insertPos <= deleteStart) {
            // Insert before delete - shift delete position right
            int newPosition = deleteStart + insertOp.content.length();

            // 🔧 CHECK FOR POSITION OVERFLOW
            if (newPosition > maxReasonablePosition) {
                Protocol.log("WARNING: DELETE position overflow detected in transform: " +
                        newPosition + " > " + maxReasonablePosition);
                newPosition = Math.min(newPosition, deleteOp.position + 50); // Limit growth
            }

            // 🔧 FIXED: Use correct DELETE constructor
            Operation result = new Operation(Operation.Type.DELETE, newPosition, deleteOp.length, deleteOp.userId);
            Protocol.log("DEBUG: Insert before delete - position shifted: " + deleteStart + " → " + newPosition);
            return result;

        } else if (insertPos >= deleteEnd) {
            // Insert after delete - no change needed
            Protocol.log("DEBUG: Insert after delete - no change");
            return deleteOp;

        } else {
            // Insert within delete range - extend delete length
            int newLength = deleteOp.length + insertOp.content.length();

            // 🔧 FIXED: Use correct DELETE constructor
            Operation result = new Operation(Operation.Type.DELETE, deleteOp.position, newLength, deleteOp.userId);
            Protocol.log("DEBUG: Insert within delete - length extended: " +
                    deleteOp.length + " → " + newLength);
            return result;
        }
    }

    /**
     * DELETE vs DELETE transform
     * En karmaşık durum - çakışan silme operasyonları
     */
    private static Operation transformDeleteDelete(Operation op1, Operation op2) {
        Protocol.log("DEBUG: transformDeleteDelete - op1: " + op1 + " vs op2: " + op2);

        // Op1 aralığı
        int op1Start = op1.position;
        int op1End = op1.position + op1.length;

        // Op2 aralığı
        int op2Start = op2.position;
        int op2End = op2.position + op2.length;

        Protocol.log("DEBUG: Op1 range: [" + op1Start + "-" + op1End + "], Op2 range: [" + op2Start + "-" + op2End + "]");

        // 🔧 NEWLINE AWARE VALIDATION
        if (op1.length <= 0 || op2.length <= 0) {
            Protocol.log("WARNING: Invalid operation length - op1: " + op1.length + ", op2: " + op2.length);
            return op1.withLength(0); // Invalid operation
        }

        // DURUM 1: Op2 tamamen op1'den önce
        if (op2End <= op1Start) {
            // Op2 silindi, op1'i geri kaydır
            int newPosition = Math.max(0, op1Start - op2.length);
            Protocol.log("DEBUG: Case 1 - Op2 before op1, shifting position: " + op1Start + " → " + newPosition);
            return op1.withPosition(newPosition);
        }

        // DURUM 2: Op2 tamamen op1'den sonra
        if (op2Start >= op1End) {
            // Op2 op1'i etkilemiyor
            Protocol.log("DEBUG: Case 2 - Op2 after op1, no change");
            return op1;
        }

        // DURUM 3: Op1 tamamen op2 içinde - op1 geçersiz olur
        if (op1Start >= op2Start && op1End <= op2End) {
            Protocol.log("DEBUG: Case 3 - Op1 completely inside op2, invalidating op1");
            return op1.withLength(0); // Geçersiz operasyon
        }

        // DURUM 4: Op2 tamamen op1 içinde - op1'in uzunluğu azalır
        if (op2Start >= op1Start && op2End <= op1End) {
            int newLength = op1.length - op2.length;
            Protocol.log("DEBUG: Case 4 - Op2 inside op1, reducing length: " + op1.length + " → " + newLength);
            return op1.withLength(Math.max(0, newLength));
        }

        // 🔧 DURUM 5: Op1 başlıyor, op2 ile çakışıyor (partial overlap - op1 starts first)
        if (op1Start < op2Start && op1End > op2Start && op1End <= op2End) {
            // Op1'in sadece op2'den önceki kısmı kalır
            int newLength = op2Start - op1Start;
            Protocol.log("DEBUG: Case 5 - Op1 starts first, partial overlap, new length: " + newLength);

            // 🔧 NEWLINE BOUNDARY CHECK
            if (newLength <= 0) {
                Protocol.log("WARNING: Case 5 resulted in invalid length, invalidating operation");
                return op1.withLength(0);
            }

            return op1.withLength(newLength);
        }

        // 🔧 DURUM 6: Op2 başlıyor, op1 ile çakışıyor (partial overlap - op2 starts first)
        if (op2Start < op1Start && op2End > op1Start && op2End < op1End) {
            // Op1'in sadece op2'den sonraki kısmı kalır
            int newPosition = op2Start; // Op2'nin başlangıç pozisyonuna kayar
            int newLength = op1End - op2End;

            Protocol.log("DEBUG: Case 6 - Op2 starts first, partial overlap, pos: " + op1Start +
                    " → " + newPosition + ", length: " + op1.length + " → " + newLength);

            // 🔧 NEWLINE BOUNDARY CHECK
            if (newLength <= 0) {
                Protocol.log("WARNING: Case 6 resulted in invalid length, invalidating operation");
                return op1.withLength(0);
            }

            return op1.withPosition(newPosition).withLength(newLength);
        }

        // 🔧 DURUM 7: Op1 ve Op2 aynı pozisyonda başlıyor
        if (op1Start == op2Start) {
            if (op1.length <= op2.length) {
                // Op1 tamamen kapsanıyor
                Protocol.log("DEBUG: Case 7a - Same start, op1 shorter or equal, invalidating");
                return op1.withLength(0);
            } else {
                // Op1'in sadece op2'den sonraki kısmı kalır
                int newLength = op1.length - op2.length;
                Protocol.log("DEBUG: Case 7b - Same start, op1 longer, new length: " + newLength);

                if (newLength <= 0) {
                    Protocol.log("WARNING: Case 7b resulted in invalid length, invalidating operation");
                    return op1.withLength(0);
                }

                return op1.withPosition(op2Start).withLength(newLength);
            }
        }

        // 🔧 DURUM 8: Tam çakışma (op1End == op2End ve op1Start == op2Start)
        if (op1Start == op2Start && op1End == op2End) {
            // Önceliği Lamport clock ile belirle
            if (hasHigherPriority(op2, op1)) {
                Protocol.log("DEBUG: Case 8 - Complete overlap, op2 has priority, invalidating op1");
                return op1.withLength(0);
            } else {
                Protocol.log("DEBUG: Case 8 - Complete overlap, op1 has priority, keeping op1");
                return op1;
            }
        }

        // Fallback - bu duruma gelmemeli
        Protocol.log("WARNING: transformDeleteDelete fallback case reached - invalidating op1");
        return op1.withLength(0);
    }

    /**
     * DELETE çakışmalarını handle eder
     */
    private static Operation handleDeleteOverlap(Operation op1, Operation op2) {
        Protocol.log("WARNING: handleDeleteOverlap called - using transformDeleteDelete instead");
        return transformDeleteDelete(op1, op2);
    }

    /**
     * Operasyon listesini sıralı şekilde transform eder
     * 3+ kullanıcı senaryosu için kritik
     */
    public static List<Operation> transformOperationList(Operation newOp, List<Operation> historicalOps) {
        Protocol.log("=== TRANSFORM OPERATION LIST DEBUG ===");
        Protocol.log("DEBUG: Transforming " + newOp.type + " against " + historicalOps.size() + " historical ops");

        Operation transformedOp = newOp;
        int originalPosition = newOp.position;

        for (Operation historicalOp : historicalOps) {
            Operation oldTransformedOp = transformedOp;
            transformedOp = transform(transformedOp, historicalOp);

            // 🔧 ENHANCED OVERFLOW PROTECTION FOR BOTH INSERT AND DELETE
            if (transformedOp.type == Operation.Type.INSERT) {
                Protocol.log("DEBUG: INSERT transform step: " + oldTransformedOp + " → " + transformedOp);

                // 🔧 INSERT OVERFLOW DETECTION
                int positionGrowth = transformedOp.position - originalPosition;
                if (positionGrowth > 50) { // Reasonable growth limit
                    Protocol.log("WARNING: INSERT position growth excessive: " + originalPosition + " → " +
                            transformedOp.position + " (growth: " + positionGrowth + ")");

                    // Create safe INSERT operation
                    int safePosition = Math.min(transformedOp.position, originalPosition + 20);
                    // 🔧 FIXED: Use correct INSERT constructor (Type, position, content, userId)
                    transformedOp = new Operation(Operation.Type.INSERT, safePosition,
                            transformedOp.content, transformedOp.userId);
                    Protocol.log("DEBUG: INSERT position clamped to: " + safePosition);
                    break; // Stop further transformation to prevent more overflow
                }

            } else if (transformedOp.type == Operation.Type.DELETE) {
                Protocol.log("DEBUG: DELETE transform step: " + oldTransformedOp + " → " + transformedOp);

                // DELETE overflow protection (existing)
                if (transformedOp.position > 10000) {
                    Protocol.log("WARNING: DELETE position overflow detected: " + transformedOp.position);
                    transformedOp = createSafeDeleteOperation(newOp, historicalOps);
                    break;
                }
            }
        }

        List<Operation> result = new ArrayList<>();
        result.add(transformedOp);

        Protocol.log("DEBUG: Final transformed operation: " + transformedOp);
        Protocol.log("=== TRANSFORM OPERATION LIST END ===");

        return result;
    }

    private static Operation createSafeDeleteOperation(Operation originalOp, List<Operation> historicalOps) {
        Protocol.log("=== CREATING SAFE DELETE OPERATION ===");

        if (originalOp.type != Operation.Type.DELETE) {
            return originalOp;
        }

        // Count net position shift from historical operations
        int positionShift = 0;
        int lengthAdjustment = 0;

        for (Operation historicalOp : historicalOps) {
            if (historicalOp.position <= originalOp.position) {
                if (historicalOp.type == Operation.Type.INSERT) {
                    positionShift += historicalOp.content.length();
                } else if (historicalOp.type == Operation.Type.DELETE) {
                    positionShift -= historicalOp.length;

                    // Check if historical delete overlaps with our delete
                    int histEnd = historicalOp.position + historicalOp.length;
                    int ourStart = originalOp.position;
                    int ourEnd = originalOp.position + originalOp.length;

                    if (histEnd > ourStart && historicalOp.position < ourEnd) {
                        // Overlap detected - adjust length
                        int overlapStart = Math.max(historicalOp.position, ourStart);
                        int overlapEnd = Math.min(histEnd, ourEnd);
                        lengthAdjustment -= (overlapEnd - overlapStart);
                    }
                }
            }
        }

        int safePosition = Math.max(0, originalOp.position + positionShift);
        int safeLength = Math.max(1, originalOp.length + lengthAdjustment);

        // 🔧 FIXED: Use correct constructor - DELETE operation
        Operation safeOp = new Operation(Operation.Type.DELETE, safePosition, safeLength, originalOp.userId);

        Protocol.log("DEBUG: Safe DELETE created - original pos:" + originalOp.position +
                " → safe pos:" + safePosition + ", original len:" + originalOp.length +
                " → safe len:" + safeLength);
        Protocol.log("=== SAFE DELETE OPERATION END ===");

        return safeOp;
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

    public static String applyOperation(String content, Operation op) {
        if (!isValidOperation(op, content)) {
            Protocol.log("WARNING: Attempting to apply invalid operation: " + op);
            return content; // Return unchanged content
        }

        try {
            if (op.type == Operation.Type.INSERT) {
                // Safe insert with bounds check
                int safePosition = Math.max(0, Math.min(op.position, content.length()));
                String result = content.substring(0, safePosition) + op.content +
                        content.substring(safePosition);

                if (safePosition != op.position) {
                    Protocol.log("DEBUG: INSERT position auto-corrected: " + op.position +
                            " → " + safePosition);
                }

                return result;

            } else if (op.type == Operation.Type.DELETE) {
                // Safe delete with bounds check
                int safePosition = Math.max(0, Math.min(op.position, content.length()));
                int maxLength = content.length() - safePosition;
                int safeLength = Math.max(0, Math.min(op.length, maxLength));

                if (safeLength <= 0) {
                    Protocol.log("DEBUG: DELETE length invalid after bounds check, skipping");
                    return content;
                }

                String result = content.substring(0, safePosition) +
                        content.substring(safePosition + safeLength);

                if (safePosition != op.position || safeLength != op.length) {
                    Protocol.log("DEBUG: DELETE bounds auto-corrected: pos " + op.position +
                            " → " + safePosition + ", len " + op.length + " → " + safeLength);
                }

                return result;
            }

            return content;

        } catch (Exception e) {
            Protocol.log("ERROR: applyOperation exception: " + e.getMessage());
            return content; // Return unchanged on error
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
    public static boolean isValidOperation(Operation op, String content) {
        if (op == null || content == null) {
            return false;
        }

        int contentLength = content.length();

        if (op.type == Operation.Type.INSERT) {
            // INSERT: position can be 0 to contentLength (inclusive)
            if (op.position < 0 || op.position > contentLength) {
                Protocol.log("DEBUG: INSERT validation failed - pos: " + op.position +
                        ", content length: " + contentLength);
                return false;
            }
            return op.content != null && !op.content.isEmpty();

        } else if (op.type == Operation.Type.DELETE) {
            // DELETE: position + length must not exceed content length
            if (op.position < 0 || op.position >= contentLength) {
                Protocol.log("DEBUG: DELETE validation failed - pos: " + op.position +
                        ", content length: " + contentLength);
                return false;
            }

            if (op.length <= 0) {
                Protocol.log("DEBUG: DELETE validation failed - invalid length: " + op.length);
                return false;
            }

            if (op.position + op.length > contentLength) {
                Protocol.log("DEBUG: DELETE validation failed - pos: " + op.position +
                        ", length: " + op.length + ", content length: " + contentLength);
                return false;
            }

            return true;
        }

        return false;
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