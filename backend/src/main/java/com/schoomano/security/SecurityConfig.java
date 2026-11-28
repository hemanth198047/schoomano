package com.schoomano.security;

import com.schoomano.platform.AppProperties;
import java.util.Arrays;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, AppProperties props, Environment env, SignInService signIn, ApiKeyService apiKeys,
                           IdempotencyService idempotency) throws Exception {
        http
            // Stateless bearer-token API: no cookies, so CSRF tokens do not apply.
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health/**", "/actuator/health", "/actuator/info").permitAll()
                // Parent sign-in happens before there is a session (ADR 0009).
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/otp/request", "/api/v1/auth/otp/verify",
                    "/api/v1/auth/token/refresh").permitAll()
                // The payment provider calls this with no session; the body signature is the credential.
                .requestMatchers(HttpMethod.POST, "/api/v1/payments/webhook").permitAll()
                .anyRequest().authenticated())
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            // Parent sessions ("Bearer smo_...") from the phone OTP; works with both staff sign-in modes below.
            .addFilterBefore(new SessionTokenFilter(signIn), UsernamePasswordAuthenticationFilter.class)
            // Integration API keys ("Bearer smk_..."): read-only service identities.
            .addFilterBefore(new ApiKeyFilter(apiKeys), UsernamePasswordAuthenticationFilter.class)
            // Repeated writes with the same Idempotency-Key return the first answer instead of doing the work again.
            .addFilterAfter(new IdempotencyFilter(idempotency), AuthorizationFilter.class);

        if (props.auth() != null && props.auth().devMode()) {
            if (Arrays.asList(env.getActiveProfiles()).contains("prod")) {
                throw new IllegalStateException("schoomano.auth.dev-mode must never be enabled with the prod profile");
            }
            http.addFilterBefore(new DevTokenFilter(), UsernamePasswordAuthenticationFilter.class);
            http.httpBasic(b -> b.disable());
        } else {
            // Production: OIDC access tokens. Set SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI.
            // A parent session token is not a JWT: leave it to SessionTokenFilter instead of failing it as one.
            var jwtTokens = new DefaultBearerTokenResolver();
            http.oauth2ResourceServer(o -> o
                .bearerTokenResolver(request -> {
                    String header = request.getHeader("Authorization");
                    return header != null && (header.startsWith("Bearer " + SessionTokens.ACCESS_PREFIX)
                        || header.startsWith("Bearer " + SessionTokens.API_KEY_PREFIX))
                        ? null : jwtTokens.resolve(request);
                })
                .jwt(Customizer.withDefaults()));
        }
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(AppProperties props) {
        CorsConfiguration c = new CorsConfiguration();
        c.setAllowedOrigins(props.cors() == null ? List.of() : props.cors().allowedOrigins());
        c.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        c.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Correlation-Id",
            "X-Schoomano-Tenant", "Idempotency-Key"));
        c.setExposedHeaders(List.of("X-Correlation-Id"));
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", c);
        return source;
    }
}
