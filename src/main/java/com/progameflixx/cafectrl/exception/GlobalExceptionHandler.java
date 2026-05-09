package com.progameflixx.cafectrl.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

@ControllerAdvice
public class GlobalExceptionHandler {

    // This catches the Java equivalent of FastAPI's HTTPException
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleHttpException(ResponseStatusException ex) {
        Map<String, String> body = new HashMap<>();
        body.put("detail", ex.getReason());
        return new ResponseEntity<>(body, ex.getStatusCode());
    }

    // Catch-all for unexpected server errors
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleGeneralException(Exception ex) {
        Map<String, String> body = new HashMap<>();
        body.put("detail", "An unexpected error occurred: " + ex.getMessage());
        return ResponseEntity.internalServerError().body(body);
    }
}
