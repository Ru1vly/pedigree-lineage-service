package com.edevlet.lineage.web;

import com.edevlet.lineage.domain.exception.DuplicateRequestException;
import com.edevlet.lineage.domain.exception.LineageNotFoundException;
import com.edevlet.lineage.domain.exception.LineageResultNotReadyException;
import com.edevlet.lineage.domain.exception.RateLimitExceededException;
import com.edevlet.lineage.domain.exception.StreamCapacityExceededException;
import com.edevlet.lineage.domain.exception.UnauthorizedTaskAccessException;
import com.edevlet.lineage.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(LineageNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(LineageNotFoundException ex, HttpServletRequest request) {
        log.warn("Lineage task not found: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(UnauthorizedTaskAccessException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedTaskAccessException ex, HttpServletRequest request) {
        log.warn("Unauthorized access: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, "UNAUTHORIZED_TASK_ACCESS", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(LineageResultNotReadyException.class)
    public ResponseEntity<ErrorResponse> handleResultNotReady(LineageResultNotReadyException ex, HttpServletRequest request) {
        log.warn("Document requested before result was ready: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "RESULT_NOT_READY", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(DuplicateRequestException.class)
    public ResponseEntity<ErrorResponse> handleDuplicate(DuplicateRequestException ex, HttpServletRequest request) {
        log.warn("Duplicate request: {}", ex.getMessage());
        return buildErrorResponse(HttpStatus.CONFLICT, "DUPLICATE_REQUEST", ex.getMessage(), request.getRequestURI(), null);
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ErrorResponse> handleRateLimit(RateLimitExceededException ex, HttpServletRequest request) {
        log.warn("Rate limit exceeded: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "60")
                .body(buildErrorPayload(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMIT_EXCEEDED", ex.getMessage(), request.getRequestURI(), null));
    }

    @ExceptionHandler(StreamCapacityExceededException.class)
    public ResponseEntity<ErrorResponse> handleStreamCapacity(StreamCapacityExceededException ex, HttpServletRequest request) {
        log.warn("Progress stream refused: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "5")
                .body(buildErrorPayload(HttpStatus.SERVICE_UNAVAILABLE, "STREAM_CAPACITY_EXCEEDED", ex.getMessage(), request.getRequestURI(), null));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fieldErrors = extractValidationFieldErrors(ex);
        return buildErrorResponse(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Invalid request payload parameters", request.getRequestURI(), fieldErrors);
    }

    /**
     * Authorization refusals, as 403.
     *
     * <p>Without this they fell to the catch-all below and came back as 500. That is invisible
     * today only because {@code SecurityConfig}'s URL matchers reject before the request is ever
     * dispatched to a controller - the admin path is denied by the filter chain, so
     * {@code @PreAuthorize} never gets to speak. The moment an endpoint is protected by method
     * security alone, which is a perfectly ordinary thing to add, "you may not do that" would have
     * been reported as "this service is broken": the wrong status, the wrong error code, and an
     * ERROR-level stack trace on every denial. Spring Security 6's {@code AuthorizationDeniedException}
     * extends {@code AccessDeniedException}, so both arrive here.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex, HttpServletRequest request) {
        log.warn("Access denied on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                "You do not have permission to perform this operation", request.getRequestURI(), null);
    }

    /** Authentication failures raised inside a dispatched request, as 401 rather than 500. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuthentication(AuthenticationException ex, HttpServletRequest request) {
        log.warn("Authentication failure on {}: {}", request.getRequestURI(), ex.getMessage());
        return buildErrorResponse(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED",
                "Authentication is required to access this resource", request.getRequestURI(), null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex, HttpServletRequest request) {
        log.error("Unhandled internal server error: {}", ex.getMessage(), ex);
        return buildErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_SERVER_ERROR", "An unexpected internal error occurred", request.getRequestURI(), null);
    }

    private Map<String, String> extractValidationFieldErrors(MethodArgumentNotValidException exception) {
        Map<String, String> errors = new HashMap<>();
        for (FieldError fieldError : exception.getBindingResult().getFieldErrors()) {
            errors.put(fieldError.getField(), fieldError.getDefaultMessage());
        }
        return errors;
    }

    private ResponseEntity<ErrorResponse> buildErrorResponse(
            HttpStatus status,
            String errorCode,
            String message,
            String requestPath,
            Map<String, String> validationErrors) {
        return ResponseEntity.status(status).body(buildErrorPayload(status, errorCode, message, requestPath, validationErrors));
    }

    private ErrorResponse buildErrorPayload(
            HttpStatus status,
            String errorCode,
            String message,
            String requestPath,
            Map<String, String> validationErrors) {
        return ErrorResponse.builder()
                .timestamp(Instant.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .errorCode(errorCode)
                .message(message)
                .path(requestPath)
                .traceId(MDC.get("traceId"))
                .validationErrors(validationErrors)
                .build();
    }
}
