package com.guoyongzheng.training.web.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> badRequest(IllegalArgumentException exception) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ApiError("BAD_REQUEST", exception.getMessage(), Instant.now()));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> serverState(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError("SERVER_STATE", messageWithCauses(exception), Instant.now()));
    }

    public record ApiError(String code, String message, Instant timestamp) {
    }

    private static String messageWithCauses(RuntimeException exception) {
        StringBuilder builder = new StringBuilder(safeMessage(exception));
        Throwable cause = exception.getCause();
        int depth = 0;
        while (cause != null && depth < 3) {
            String message = safeMessage(cause);
            if (!message.isBlank()) {
                builder.append(" | cause: ").append(message);
            }
            cause = cause.getCause();
            depth++;
        }
        return builder.toString();
    }

    private static String safeMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null ? throwable.getClass().getSimpleName() : message;
    }
}
