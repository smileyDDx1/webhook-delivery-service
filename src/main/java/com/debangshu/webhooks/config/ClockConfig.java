package com.debangshu.webhooks.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Everything that needs "now" asks this Clock instead of calling Instant.now() directly.
 * Unit tests can then pass a fixed clock and check exact retry times, pause times, etc.
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfig {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
