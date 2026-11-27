package com.schoomano.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates "Authorization: Bearer smk_..." (an integration API key). A key that is unknown, revoked or expired
 * leaves the request unauthenticated, so it ends in a plain 401.
 */
public class ApiKeyFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final ApiKeyService keys;

    public ApiKeyFilter(ApiKeyService keys) {
        this.keys = keys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX + SessionTokens.API_KEY_PREFIX)) {
            keys.authenticate(header.substring(PREFIX.length())).ifPresent(identity ->
                SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(identity, null, List.of())));
        }
        chain.doFilter(request, response);
    }
}
