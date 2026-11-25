package com.progameflixx.cafectrl.logging;

import org.slf4j.MDC;

/**
 * Keys placed in the SLF4J MDC for every request. They are printed on each log line
 * via the "logging.pattern.level" setting in application.yaml, so all logs belonging
 * to one request / user / cafe can be grepped together.
 */
public final class LogContext {

    public static final String REQUEST_ID = "reqId";
    public static final String USER = "user";
    public static final String ROLE = "role";
    public static final String CAFE_ID = "cafeId";

    private LogContext() {
    }

    public static void putUser(String user, String role) {
        if (user != null) MDC.put(USER, user);
        if (role != null) MDC.put(ROLE, role);
    }

    public static void putCafeId(String cafeId) {
        if (cafeId != null) MDC.put(CAFE_ID, cafeId);
    }
}
