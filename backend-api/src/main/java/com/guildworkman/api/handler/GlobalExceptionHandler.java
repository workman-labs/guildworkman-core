package com.guildworkman.api.handler;

import com.guildworkman.api.exceptions.*;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestControllerAdvice
public class GlobalExceptionHandler {

    // --- Authentication & RBAC (issue #23) -------------------------------
    // All auth errors keep the shared {error, success:false} contract, but map
    // to accurate status codes instead of a blanket 400.

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<?> handleEmailExists(EmailAlreadyExistsException exception) {
        return ResponseEntity.status(CONFLICT)
                .body(Map.of("error", exception.getMessage(), "success", false));
    }

    @ExceptionHandler(TokenRefreshException.class)
    public ResponseEntity<?> handleTokenRefresh(TokenRefreshException exception) {
        return ResponseEntity.status(UNAUTHORIZED)
                .body(Map.of("error", exception.getMessage(), "success", false));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<?> handleAuthentication(AuthenticationException exception) {
        // Uniform message so we don't leak whether it was the email or password
        // that was wrong.
        return ResponseEntity.status(UNAUTHORIZED)
                .body(Map.of("error", "Invalid email or password", "success", false));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<?> handleValidation(MethodArgumentNotValidException exception) {
        Map<String, Object> fieldErrors = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "Validation failed");
        body.put("fieldErrors", fieldErrors);
        body.put("success", false);
        return ResponseEntity.status(BAD_REQUEST).body(body);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<?>handleUserNotFound(UserNotFoundException exception){
        return ResponseEntity.status(BAD_REQUEST)
                .body(Map.of(
                        "error", exception.getMessage(),
                        "success", false));
    }
    @ExceptionHandler(AppointmentNotFoundException.class)
    public ResponseEntity<?> handleAppointmentNotFound(AppointmentNotFoundException exception){
        return ResponseEntity.status(BAD_REQUEST)
                .body(Map.of(
                        "error", exception.getMessage(),
                        "success", false));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> handleGlobalException(Exception exception){
        return ResponseEntity.status(BAD_REQUEST)
               .body(Map.of(
                        "error", exception.getMessage(),
                        "success", false));
    }
    @ExceptionHandler(GuildWorkmanException.class)
    public ResponseEntity<?> handleGuildWorkmanException(GuildWorkmanException exception){
        return ResponseEntity.status(BAD_REQUEST)
                .body(Map.of(
                        "error", exception.getMessage(),
                        "success", false));

    }
    @ExceptionHandler(InvalidPasswordException.class)
    public ResponseEntity<?>handleInvalidPasswordException(@NotNull InvalidPasswordException exception){
        return ResponseEntity.status(BAD_REQUEST)
                .body(Map.of("error", exception.getMessage(),
                        "success", false));
    }

    @ExceptionHandler(InvalidEmailFoundException.class)
    public ResponseEntity<?>handleInvalidEmailFoundException(InvalidEmailFoundException exception){
        return ResponseEntity.status(BAD_REQUEST)
                .body(Map.of("error", exception.getMessage(),
                        "success", false));
    }
}
