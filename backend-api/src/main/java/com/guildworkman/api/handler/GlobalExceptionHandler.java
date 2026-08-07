package com.guildworkman.api.handler;

import com.guildworkman.api.booking.service.ReservationNotFoundException;
import com.guildworkman.api.booking.service.ReservationNotHeldException;
import com.guildworkman.api.booking.service.SlotUnavailableException;
import com.guildworkman.api.escrow.service.EscrowOrchestrationNotFoundException;
import com.guildworkman.api.escrow.service.ReconciliationRequeueNotAllowedException;
import com.guildworkman.api.exceptions.*;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Central error contract for the API: every exception raised inside a controller
 * or during request binding/validation is rendered as an RFC 7807
 * (application/problem+json) body — {@code type/title/status/detail}, plus a
 * {@code errors} extension for field/parameter-level validation failures.
 *
 * <p>Every handler returns a {@link ResponseEntity} with the status set
 * explicitly, rather than relying on the framework to infer it from the
 * {@link ProblemDetail}'s own {@code status} field.
 *
 * <p>401/403 raised by the Spring Security filter chain (before the
 * DispatcherServlet) are out of reach here; they're rendered in the same shape
 * by {@link com.guildworkman.api.security.RestAuthenticationEntryPoint} and
 * {@link com.guildworkman.api.security.RestAccessDeniedHandler}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static ResponseEntity<ProblemDetail> respond(HttpStatus status, String slug, String title, String detail) {
        return ResponseEntity.status(status).body(ProblemDetails.of(status, slug, title, detail));
    }

    // --- Domain exceptions ------------------------------------------------

    @ExceptionHandler(EmailAlreadyExistsException.class)
    public ResponseEntity<ProblemDetail> handleEmailExists(EmailAlreadyExistsException exception) {
        return respond(HttpStatus.CONFLICT, "email-already-exists",
                "Email already registered", exception.getMessage());
    }

    @ExceptionHandler(TokenRefreshException.class)
    public ResponseEntity<ProblemDetail> handleTokenRefresh(TokenRefreshException exception) {
        return respond(HttpStatus.UNAUTHORIZED, "invalid-refresh-token",
                "Refresh token rejected", exception.getMessage());
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthentication(AuthenticationException exception) {
        // Uniform message so we don't leak whether it was the email or password
        // that was wrong.
        return respond(HttpStatus.UNAUTHORIZED, "invalid-credentials",
                "Authentication failed", "Invalid email or password");
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException exception) {
        return respond(HttpStatus.FORBIDDEN, "access-denied",
                "Access denied", "You do not have permission to perform this action");
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleUserNotFound(UserNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "user-not-found",
                "User not found", exception.getMessage());
    }

    @ExceptionHandler(AppointmentNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleAppointmentNotFound(AppointmentNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "appointment-not-found",
                "Appointment not found", exception.getMessage());
    }

    @ExceptionHandler(ReservationNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleReservationNotFound(ReservationNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "reservation-not-found",
                "Slot reservation not found", exception.getMessage());
    }

    /** Losing a booking race is a conflict, not a client error — the request was well-formed. */
    @ExceptionHandler(SlotUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleSlotUnavailable(SlotUnavailableException exception) {
        return respond(HttpStatus.CONFLICT, "slot-unavailable",
                "Slot unavailable", exception.getMessage());
    }

    @ExceptionHandler(ReservationNotHeldException.class)
    public ResponseEntity<ProblemDetail> handleReservationNotHeld(ReservationNotHeldException exception) {
        return respond(HttpStatus.CONFLICT, "reservation-not-held",
                "Slot reservation is no longer held", exception.getMessage());
    }

    @ExceptionHandler(EscrowOrchestrationNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleEscrowOrchestrationNotFound(EscrowOrchestrationNotFoundException exception) {
        return respond(HttpStatus.NOT_FOUND, "escrow-orchestration-not-found",
                "Escrow orchestration request not found", exception.getMessage());
    }

    @ExceptionHandler(ReconciliationRequeueNotAllowedException.class)
    public ResponseEntity<ProblemDetail> handleReconciliationRequeueNotAllowed(ReconciliationRequeueNotAllowedException exception) {
        return respond(HttpStatus.CONFLICT, "reconciliation-requeue-not-allowed",
                "Reconciliation requeue not allowed", exception.getMessage());
    }

    @ExceptionHandler(InvalidPasswordException.class)
    public ResponseEntity<ProblemDetail> handleInvalidPasswordException(InvalidPasswordException exception) {
        return respond(HttpStatus.BAD_REQUEST, "invalid-password",
                "Invalid password", exception.getMessage());
    }

    @ExceptionHandler(InvalidEmailFoundException.class)
    public ResponseEntity<ProblemDetail> handleInvalidEmailFoundException(InvalidEmailFoundException exception) {
        return respond(HttpStatus.BAD_REQUEST, "invalid-email",
                "Invalid email", exception.getMessage());
    }

    @ExceptionHandler(GuildWorkmanException.class)
    public ResponseEntity<ProblemDetail> handleGuildWorkmanException(GuildWorkmanException exception) {
        return respond(HttpStatus.BAD_REQUEST, "bad-request",
                "Request could not be processed", exception.getMessage());
    }

    // --- Bean validation ----------------------------------------------------

    /** Validation failures on {@code @RequestParam}/{@code @PathVariable} (needs {@code @Validated} on the controller). */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleConstraintViolation(ConstraintViolationException exception) {
        Map<String, String> errors = new LinkedHashMap<>();
        exception.getConstraintViolations().forEach(violation ->
                errors.put(violation.getPropertyPath().toString(), violation.getMessage()));
        ProblemDetail problemDetail = ProblemDetails.of(HttpStatus.BAD_REQUEST, "constraint-violation",
                "Constraint violation", "One or more request parameters are invalid");
        problemDetail.setProperty("errors", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException exception) {
        String requiredType = exception.getRequiredType() != null
                ? exception.getRequiredType().getSimpleName()
                : "a different type";
        return respond(HttpStatus.BAD_REQUEST, "type-mismatch", "Invalid parameter type",
                "Parameter '%s' should be of type %s".formatted(exception.getName(), requiredType));
    }

    /** Validation failures on {@code @Valid @RequestBody} DTOs. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        for (FieldError error : exception.getBindingResult().getFieldErrors()) {
            errors.put(error.getField(), error.getDefaultMessage());
        }
        ProblemDetail problemDetail = ProblemDetails.of(HttpStatus.BAD_REQUEST, "validation-error",
                "Validation failed", "One or more fields failed validation");
        problemDetail.setProperty("errors", errors);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problemDetail = ProblemDetails.of(HttpStatus.BAD_REQUEST, "malformed-request",
                "Malformed request body", "The request body is missing or could not be parsed");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    @Override
    protected ResponseEntity<Object> handleMissingServletRequestParameter(
            MissingServletRequestParameterException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problemDetail = ProblemDetails.of(HttpStatus.BAD_REQUEST, "missing-parameter",
                "Missing request parameter", exception.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problemDetail);
    }

    @Override
    protected ResponseEntity<Object> handleHttpRequestMethodNotSupported(
            HttpRequestMethodNotSupportedException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        ProblemDetail problemDetail = ProblemDetails.of(HttpStatus.METHOD_NOT_ALLOWED, "method-not-allowed",
                "Method not allowed", exception.getMessage());
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(problemDetail);
    }

    // --- Fallback -------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGlobalException(Exception exception) {
        log.error("Unhandled exception", exception);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "Internal server error", "An unexpected error occurred. Please try again later.");
    }
}
