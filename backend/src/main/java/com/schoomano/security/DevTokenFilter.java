package com.schoomano.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * LOCAL DEVELOPMENT ONLY. Accepts "Authorization: Bearer dev:&lt;subject&gt;" for seeded synthetic users.
 * Registered only when schoomano.auth.dev-mode=true, which SecurityConfig refuses under the prod profile.
 */
public class DevTokenFilter extends OncePerRequestFilter {

    private static final Pattern SUBJECT = Pattern.compile("^[a-z0-9-]{3,40}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer dev:")) {
            String subject = header.substring("Bearer dev:".length());
            if (SUBJECT.matcher(subject).matches()) {
                var auth = UsernamePasswordAuthenticationToken.authenticated(
                    new ExternalIdentity("dev", subject), null, List.of());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }
}
