package com.progameflixx.cafectrl.audit;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Makes values safe to store: masks anything that looks like a secret (by key name, or JWT-shaped strings)
 * and reduces values to JSON primitives (String / Number / Boolean / Map / List). Applied centrally by
 * {@link AuditService} to every changes / details map, so no call site can leak a secret by accident.
 */
public final class AuditSanitizer {

    public static final String MASK = "***";

    /** Keys whose values are never stored. Matched case-insensitively anywhere in the key. */
    private static final Pattern SECRET_KEY = Pattern.compile(
            "(?i).*(pass(word)?|pwd|token|secret|otp|pin|authorization|cookie|credential|api[_-]?key|salt).*");

    /** A JWT: three base64url segments, the first starting with "eyJ". */
    private static final Pattern JWT = Pattern.compile("^eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]*$");

    static final int MAX_STRING = 2000;
    static final int MAX_DEPTH = 6;

    private AuditSanitizer() {}

    public static boolean isSecretKey(String key) {
        return key != null && SECRET_KEY.matcher(key).matches();
    }

    /** Sanitised deep copy; null in, null out. Values must already be JSON-like (see AuditService#toJsonLike). */
    public static Map<String, Object> sanitize(Map<String, ?> in) {
        if (in == null) return null;
        Object out = clean(in, 0);
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) out;
        return m;
    }

    private static Object clean(Object v, int depth) {
        if (v == null) return null;
        if (depth > MAX_DEPTH) return String.valueOf(v);
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String key = String.valueOf(e.getKey());
                out.put(key, isSecretKey(key) ? MASK : clean(e.getValue(), depth + 1));
            }
            return out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>(c.size());
            for (Object o : c) out.add(clean(o, depth + 1));
            return out;
        }
        if (v instanceof String s) {
            if (JWT.matcher(s).matches()) return MASK;
            return s.length() > MAX_STRING ? s.substring(0, MAX_STRING) + "…" : s;
        }
        if (v instanceof BigDecimal bd) return bd;
        if (v instanceof Number || v instanceof Boolean) return v;
        return clean(String.valueOf(v), depth);
    }
}
