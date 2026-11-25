package com.progameflixx.cafectrl.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    // This catches the Java equivalent of FastAPI's HTTPException
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleHttpException(ResponseStatusException ex, HttpServletRequest request) {
        logger.warn("{} {} -> {} {}", request.getMethod(), request.getRequestURI(), ex.getStatusCode(), ex.getReason());
        Map<String, String> body = new HashMap<>();
        body.put("detail", ex.getReason());
        return new ResponseEntity<>(body, ex.getStatusCode());
    }

    // Catch-all for unexpected server errors
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneralException(Exception ex, HttpServletRequest request) {
        logger.error("Unhandled exception on {} {}: {}", request.getMethod(), request.getRequestURI(), ex.toString(), ex);
        Map<String, String> body = new HashMap<>();
        body.put("detail", "An unexpected error occurred: " + ex.getMessage());
        return ResponseEntity.internalServerError().body(body);
    }
}
