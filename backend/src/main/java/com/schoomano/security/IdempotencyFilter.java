package com.schoomano.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Idempotency-Key for state-changing requests. The first request with a key runs normally and, if it succeeds with a JSON
 * answer, the answer is kept for 24 hours. The same request repeated with the same key gets that answer again (header
 * Idempotent-Replay: true) and does no work. The same key on a different request is refused with 422, and a repeat while the
 * first is still running with 409. Failed requests are forgotten so the client may retry with the same key.
 * Runs after authentication; unauthenticated, multipart and bodies over 1 MB are not handled and pass through unchanged.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    public static final String HEADER = "Idempotency-Key";
    static final int MAX_REQUEST = 1024 * 1024;
    static final int MAX_STORED = 256 * 1024;
    private static final Pattern KEY = Pattern.compile("^[A-Za-z0-9._:-]{8,100}$");
    private static final Set<String> METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");

    private final IdempotencyService service;

    public IdempotencyFilter(IdempotencyService service) {
        this.service = service;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = request.getHeader(HEADER);
        String caller = caller();
        String type = request.getContentType();
        if (key == null || caller == null || !METHODS.contains(request.getMethod()) || !request.getRequestURI().startsWith("/api/")
            || (type != null && type.toLowerCase().startsWith("multipart/"))) {
            chain.doFilter(request, response);
            return;
        }
        if (!KEY.matcher(key).matches()) {
            problem(response, 400, "invalid_idempotency_key", "Use 8 to 100 letters, digits, dots, dashes, colons or underscores for the Idempotency-Key.");
            return;
        }
        byte[] body = request.getInputStream().readNBytes(MAX_REQUEST + 1);
        if (body.length > MAX_REQUEST) {
            problem(response, 413, "request_too_large", "A request with an Idempotency-Key can be at most 1 MB.");
            return;
        }
        String scope = hex(sha256((caller + "|" + nullToEmpty(request.getHeader(RequestContextResolver.TENANT_HEADER))).getBytes(StandardCharsets.UTF_8)));
        byte[] fingerprint = sha256(concat((request.getMethod() + "\n" + request.getRequestURI() + "?" + nullToEmpty(request.getQueryString()) + "\n")
            .getBytes(StandardCharsets.UTF_8), body));
        IdempotencyService.Begin begin = service.begin(scope, key, fingerprint);
        switch (begin.outcome()) {
            case "REPLAY" -> {
                response.setStatus(begin.status());
                response.setContentType(begin.contentType());
                response.setHeader("Idempotent-Replay", "true");
                response.getOutputStream().write(begin.body().getBytes(StandardCharsets.UTF_8));
            }
            case "MISMATCH" -> problem(response, 422, "idempotency_key_reused", "This Idempotency-Key was already used for a different request.");
            case "BUSY" -> {
                response.setHeader("Retry-After", "1");
                problem(response, 409, "request_in_progress", "The first request with this Idempotency-Key is still running. Try again shortly.");
            }
            default -> run(request, response, chain, body, scope, key, fingerprint);
        }
    }

    private void run(HttpServletRequest request, HttpServletResponse response, FilterChain chain, byte[] body, String scope, String key, byte[] fingerprint)
            throws ServletException, IOException {
        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        boolean kept = false;
        try {
            chain.doFilter(new CachedBodyRequest(request, body), wrapped);
            byte[] out = wrapped.getContentAsByteArray();
            String contentType = wrapped.getContentType();
            int status = wrapped.getStatus();
            if (status >= 200 && status < 300 && out.length > 0 && out.length <= MAX_STORED && contentType != null
                && contentType.toLowerCase().contains("json")) {
                service.finish(scope, key, fingerprint, status, new String(out, StandardCharsets.UTF_8), contentType);
                kept = true;
            }
        } finally {
            if (!kept) {
                service.abandon(scope, key, fingerprint);
            }
            wrapped.copyBodyToResponse();
        }
    }

    /** Who is asking, as a string, or null when the request is not authenticated. */
    private static String caller() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object p = auth.getPrincipal();
        if (p instanceof ExternalIdentity e) {
            return e.issuer() + "|" + e.subject();
        }
        if (p instanceof Jwt jwt && jwt.getIssuer() != null) {
            return jwt.getIssuer() + "|" + jwt.getSubject();
        }
        return null;
    }

    private static void problem(HttpServletResponse response, int status, String code, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write("{\"type\":\"about:blank\",\"status\":" + status + ",\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}");
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static String hex(byte[] data) {
        return HexFormat.of().formatHex(data);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Serves the body that was already read, so the controller can read it again. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) {
                    return in.read(b, off, len);
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("asynchronous reads are not supported");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
