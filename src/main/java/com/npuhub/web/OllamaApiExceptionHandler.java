package com.npuhub.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class OllamaApiExceptionHandler {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Object> malformedJson(
            HttpMessageNotReadableException error,
            HttpServletRequest request
    ) {
        return response(HttpStatus.BAD_REQUEST, "invalid JSON request body", request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Object> invalidRequest(
            IllegalArgumentException error,
            HttpServletRequest request
    ) {
        HttpStatus status = error.getMessage() != null && error.getMessage().contains("not found")
                ? HttpStatus.NOT_FOUND
                : HttpStatus.BAD_REQUEST;
        return response(status, safeMessage(error), request);
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<Object> unsupported(
            UnsupportedOperationException error,
            HttpServletRequest request
    ) {
        return response(HttpStatus.NOT_IMPLEMENTED, safeMessage(error), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Object> runtimeFailure(
            IllegalStateException error,
            HttpServletRequest request
    ) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, safeMessage(error), request);
    }

    private ResponseEntity<Object> response(
            HttpStatus status,
            String message,
            HttpServletRequest request
    ) {
        if (request.getRequestURI().startsWith("/v1/")) {
            return ResponseEntity.status(status).body(Map.of(
                    "error", Map.of(
                            "message", message,
                            "type", status.is4xxClientError() ? "invalid_request_error" : "server_error",
                            "param", "",
                            "code", status.value()
                    )
            ));
        }
        return ResponseEntity.status(status).body(Map.of("error", message));
    }

    private String safeMessage(Exception error) {
        return error.getMessage() == null || error.getMessage().isBlank()
                ? "request failed"
                : error.getMessage();
    }
}
