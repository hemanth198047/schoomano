package com.schoomano;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

// No username/password users: identities come from OIDC (or dev tokens locally).
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class SchoomanoApplication {
    public static void main(String[] args) {
        SpringApplication.run(SchoomanoApplication.class, args);
    }
}
