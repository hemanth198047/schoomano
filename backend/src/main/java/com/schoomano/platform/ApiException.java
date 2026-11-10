package com.schoomano.platform;

import org.springframework.http.HttpStatus;

/** Expected, user-actionable failure. {@code code} is stable for clients; detail is safe to show. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final java.util.Map<String, Object> properties;

    public ApiException(HttpStatus status, String code, String detail) {
        this(status, code, detail, java.util.Map.of());
    }

    /** {@code properties} are extra problem-detail members, e.g. a list of user-readable problems. */
    public ApiException(HttpStatus status, String code, String detail, java.util.Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.code = code;
        this.properties = java.util.Map.copyOf(properties);
    }

    public java.util.Map<String, Object> properties() {
        return properties;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public static ApiException notFound() {
        // Same response for "does not exist" and "not allowed" to prevent record enumeration.
        return new ApiException(HttpStatus.NOT_FOUND, "not_found", "The record does not exist or you cannot access it.");
    }

    public static ApiException forbidden(String detail) {
        return new ApiException(HttpStatus.FORBIDDEN, "forbidden", detail);
    }

    public static ApiException badRequest(String code, String detail) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, detail);
    }
}
