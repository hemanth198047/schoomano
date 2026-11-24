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
 * Authenticates "Authorization: Bearer smo_..." (a parent session from the phone OTP). Anything that is not a
 * valid, live session leaves the request unauthenticated, so it ends in a plain 401. Staff OIDC tokens and dev
 * tokens are handled elsewhere and are untouched here.
 */
public class SessionTokenFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final SignInService signIn;

    public SessionTokenFilter(SignInService signIn) {
        this.signIn = signIn;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX + SessionTokens.ACCESS_PREFIX)) {
            signIn.authenticate(header.substring(PREFIX.length())).ifPresent(identity ->
                SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(identity, null, List.of())));
        }
        chain.doFilter(request, response);
    }
}
