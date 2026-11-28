package com.progameflixx.cafectrl.idempotency;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;

/**
 * Keeps a copy of the request body for requests that carry an Idempotency-Key, so {@link IdempotencyAspect} can
 * fingerprint exactly what the client sent. (Hashing the deserialised arguments is not stable: entity bodies get a
 * fresh random id / timestamp on every request, which made an identical retry look like a different request.)
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class IdempotencyBodyCachingFilter extends OncePerRequestFilter {

    /** Bodies above this are still processed normally; only the first 1 MB is fingerprinted. */
    static final int MAX_CACHED_BODY = 1024 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return request.getHeader(IdempotencyAspect.HEADER) == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        chain.doFilter(new ContentCachingRequestWrapper(request, MAX_CACHED_BODY), response);
    }
}
