package com.schoomano.platform;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("schoomano")
public record AppProperties(Auth auth, Cors cors) {
    public record Auth(boolean devMode) {
    }

    public record Cors(List<String> allowedOrigins) {
    }
}
