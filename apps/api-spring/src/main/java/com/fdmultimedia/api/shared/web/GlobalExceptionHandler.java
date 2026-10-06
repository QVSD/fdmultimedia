package com.fdmultimedia.api.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

/**
 * Minimal, uniform error-response foundation. Domain-specific exceptions
 * will be added as each module's logic is implemented.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::toString)
                .toList();
        ApiError body = ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Validation failed",
                request.getRequestURI(),
                details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /** A malformed path or query value (for example a non-UUID id) is a client error, never a logged server fault. */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        ApiError body = ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Invalid value for parameter " + ex.getName(),
                request.getRequestURI());
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * Framework-detected client errors (unknown path, wrong method or media type, unreadable body, missing parameter) are 4xx answers,
     * never logged server faults. The message is the status text only: nothing from the request is echoed back.
     */
    @ExceptionHandler({
            org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.servlet.NoHandlerFoundException.class,
            org.springframework.web.HttpRequestMethodNotSupportedException.class,
            org.springframework.web.HttpMediaTypeNotSupportedException.class,
            org.springframework.web.HttpMediaTypeNotAcceptableException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.bind.ServletRequestBindingException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<ApiError> handleClientError(Exception ex, HttpServletRequest request) {
        HttpStatus status = ex instanceof org.springframework.web.ErrorResponse response
                ? HttpStatus.valueOf(response.getStatusCode().value())
                : HttpStatus.BAD_REQUEST;
        log.debug("Client error {} for {} type={}", status.value(), request.getRequestURI(), ex.getClass().getSimpleName());
        ApiError body = ApiError.of(status.value(), status.getReasonPhrase(), status.getReasonPhrase(), request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiError> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        ApiError body = ApiError.of(
                status.value(),
                status.getReasonPhrase(),
                ex.getReason() == null ? status.getReasonPhrase() : ex.getReason(),
                request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception while processing {}", request.getRequestURI(), ex);

        ApiError body = ApiError.of(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase(),
                "Internal server error",
                request.getRequestURI());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
