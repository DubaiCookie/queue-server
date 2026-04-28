package com.skala.queue_server.exception;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(QueueException.class)
    public ResponseEntity<Map<String, String>> handleQueueException(QueueException e) {
        ErrorCode code = e.getErrorCode();
        return ResponseEntity.status(code.getHttpStatus())
                .body(Map.of("code", code.name(), "message", code.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException e) {
        ErrorCode code = ErrorCode.MISSING_REQUIRED_FIELD;
        return ResponseEntity.status(code.getHttpStatus())
                .body(Map.of("code", code.name(), "message", code.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> handleException(Exception e) {
        e.printStackTrace();
        return ResponseEntity.internalServerError()
                .body(Map.of("code", "INTERNAL_ERROR", "message", e.getMessage()));
    }
}
