package com.progameflixx.cafectrl.exception;

import com.progameflixx.cafectrl.audit.AuditActions;
import com.progameflixx.cafectrl.audit.AuditRecord;
import com.progameflixx.cafectrl.audit.AuditService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final AuditService audit;

    public GlobalExceptionHandler(AuditService audit) {
        this.audit = audit;
    }

    // This catches the Java equivalent of FastAPI's HTTPException
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleHttpException(ResponseStatusException ex, HttpServletRequest request) {
        logger.warn("{} {} -> {} {}", request.getMethod(), request.getRequestURI(), ex.getStatusCode(), ex.getReason());
        Map<String, String> body = new HashMap<>();
        body.put("detail", ex.getReason());
        return new ResponseEntity<>(body, ex.getStatusCode());
    }

    // Role checks (@PreAuthorize) failing must stay a 403, not fall into the 500 catch-all below
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, String>> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        logger.warn("Access denied on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.getMessage());
        audit.recordFailure(AuditRecord.of(AuditActions.ACCESS_DENIED)
                .description("Tried " + request.getMethod() + " " + request.getRequestURI() + " without permission")
                .detail("method", request.getMethod()).detail("path", request.getRequestURI()), "Access denied");
        Map<String, String> body = new HashMap<>();
        body.put("detail", "Access denied");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    // Catch-all for unexpected server errors
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneralException(Exception ex, HttpServletRequest request) {
        // Spring's own client errors (unknown URL -> 404, wrong HTTP method -> 405, bad body -> 400, ...) carry
        // their status; keep it instead of reporting a 500
        if (ex instanceof org.springframework.web.ErrorResponse er && er.getStatusCode().is4xxClientError()) {
            logger.warn("{} {} -> {} ({})", request.getMethod(), request.getRequestURI(), er.getStatusCode(), ex.getMessage());
            Map<String, String> body = new HashMap<>();
            body.put("detail", er.getBody().getDetail() != null ? er.getBody().getDetail() : er.getStatusCode().toString());
            return ResponseEntity.status(er.getStatusCode()).body(body);
        }
        logger.error("Unhandled exception on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.toString(), ex);
        Map<String, String> body = new HashMap<>();
        body.put("detail", "An unexpected error occurred: " + ex.getMessage());
        return ResponseEntity.internalServerError().body(body);
    }
}
