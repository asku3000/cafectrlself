package com.progameflixx.cafectrl.logging;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Runs first for every HTTP request:
 *  - assigns a request id (re-uses an incoming X-Request-Id header if it is safe),
 *  - echoes it back in the X-Request-Id response header,
 *  - logs one line when the request starts and one when it finishes (status + duration),
 *  - clears the MDC afterwards so context never leaks between pooled threads.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(RequestLoggingFilter.class);

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String requestId = resolveRequestId(request.getHeader(REQUEST_ID_HEADER));
        MDC.put(LogContext.REQUEST_ID, requestId);
        response.setHeader(REQUEST_ID_HEADER, requestId);

        String method = request.getMethod();
        String uri = request.getRequestURI(); // query string deliberately omitted (may contain customer names/phones)
        boolean preflight = "OPTIONS".equalsIgnoreCase(method);
        long startNanos = System.nanoTime();

        if (!preflight) {
            logger.info("--> {} {} from {}", method, uri, request.getRemoteAddr());
        }

        try {
            filterChain.doFilter(request, response);
        } catch (Exception ex) {
            logger.error("<-- {} {} failed after {}ms: {}", method, uri, elapsedMs(startNanos), ex.toString(), ex);
            throw ex;
        } finally {
            int status = response.getStatus();
            long ms = elapsedMs(startNanos);
            if (preflight) {
                logger.debug("<-- {} {} status={} {}ms", method, uri, status, ms);
            } else if (status >= 500) {
                logger.error("<-- {} {} status={} {}ms", method, uri, status, ms);
            } else if (status >= 400) {
                logger.warn("<-- {} {} status={} {}ms", method, uri, status, ms);
            } else {
                logger.info("<-- {} {} status={} {}ms", method, uri, status, ms);
            }
            MDC.clear();
        }
    }

    private static String resolveRequestId(String incoming) {
        if (incoming != null && SAFE_ID.matcher(incoming).matches()) {
            return incoming;
        }
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
