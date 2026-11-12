package com.debangshu.webhooks;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

// UserDetailsServiceAutoConfiguration is excluded because there are no users/passwords here, only API keys.
// Without the exclude Spring Boot creates a default "user" with a random password and logs it at startup.
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
@ConfigurationPropertiesScan
public class WebhookDeliveryApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebhookDeliveryApplication.class, args);
    }
}
