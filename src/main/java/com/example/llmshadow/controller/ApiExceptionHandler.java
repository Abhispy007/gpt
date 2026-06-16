package com.example.llmshadow.controller;

import com.example.llmshadow.service.RequestRejectedException;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RequestRejectedException.class)
    public ResponseEntity<Map<String, String>> handleRequestRejected(RequestRejectedException ex) {
        HttpStatus status = switch (ex.reason()) {
            case REQUEST_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case BACKPRESSURE -> HttpStatus.SERVICE_UNAVAILABLE;
            case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case INVALID_REQUEST, STREAMING_NOT_SUPPORTED -> HttpStatus.BAD_REQUEST;
        };
        return ResponseEntity.status(status).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(RequestNotPermitted.class)
    public ResponseEntity<Map<String, String>> handleRateLimit(RequestNotPermitted ex) {
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", "rate limit exceeded"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        return ResponseEntity
                .badRequest()
                .body(Map.of("error", "request validation failed"));
    }
}
