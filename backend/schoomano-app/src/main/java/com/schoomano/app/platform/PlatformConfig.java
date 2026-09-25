package com.schoomano.app.platform;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PlatformConfig {
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
