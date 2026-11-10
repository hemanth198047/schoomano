package com.schoomano.platform;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** RFC 9457 problem responses. Internal messages and stack traces never reach the client. */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail api(ApiException e) {
        ProblemDetail p = problem(e.status(), e.code(), e.getMessage());
        e.properties().forEach(p::setProperty);
        return p;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        ProblemDetail p = problem(HttpStatus.BAD_REQUEST, "validation_failed", "Some fields need attention.");
        p.setProperty("fields", e.getBindingResult().getFieldErrors().stream()
            .map(f -> java.util.Map.of("field", f.getField(), "message", String.valueOf(f.getDefaultMessage())))
            .toList());
        return p;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail conflict(DataIntegrityViolationException e) {
        log.warn("integrity violation: {}", e.getMostSpecificCause().getClass().getSimpleName());
        return problem(HttpStatus.CONFLICT, "conflict",
            "This change conflicts with existing data (for example an overlapping date range or duplicate key).");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
            "Something failed on our side. Quote the correlation id when contacting support.");
    }

    private static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(status, detail);
        p.setProperty("code", code);
        p.setProperty("correlationId", CorrelationIdFilter.current());
        return p;
    }
}
