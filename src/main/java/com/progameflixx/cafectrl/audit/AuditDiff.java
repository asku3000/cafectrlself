package com.progameflixx.cafectrl.audit;

import java.math.BigDecimal;
import java.util.*;

/**
 * Field-level before/after for updates. Only fields whose value actually changed are kept, as
 * {@code {"field": {"before": x, "after": y}}}. Numbers compare by value (100 == 100.0). Secret fields are
 * never included with their values (they are masked by {@link AuditSanitizer}, and reported only as changed).
 */
public final class AuditDiff {

    private AuditDiff() {}

    public static Map<String, Object> between(Map<String, ?> before, Map<String, ?> after) {
        Map<String, ?> b = before == null ? Map.of() : before;
        Map<String, ?> a = after == null ? Map.of() : after;
        Set<String> keys = new LinkedHashSet<>(b.keySet());
        keys.addAll(a.keySet());
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : keys) {
            Object x = b.get(k);
            Object y = a.get(k);
            if (sameValue(x, y)) continue;
            Map<String, Object> pair = new LinkedHashMap<>();
            pair.put("before", x);
            pair.put("after", y);
            out.put(k, pair);
        }
        return out;
    }

    static boolean sameValue(Object x, Object y) {
        if (x == y) return true;
        if (x == null || y == null) return false;
        if (x instanceof Number nx && y instanceof Number ny) {
            try {
                return new BigDecimal(nx.toString()).compareTo(new BigDecimal(ny.toString())) == 0;
            } catch (NumberFormatException e) {
                return nx.equals(ny);
            }
        }
        if (x instanceof Collection<?> cx && y instanceof Collection<?> cy) {
            return new ArrayList<>(cx).equals(new ArrayList<>(cy));
        }
        return x.equals(y);
    }

    /** Builds an ordered snapshot map from alternating key/value pairs (nulls allowed). */
    public static Map<String, Object> snapshot(Object... keyValues) {
        if (keyValues.length % 2 != 0) throw new IllegalArgumentException("snapshot needs key/value pairs");
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) m.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        return m;
    }
}
