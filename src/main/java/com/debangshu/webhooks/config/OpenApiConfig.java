package com.debangshu.webhooks.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(
                title = "Webhook Delivery Service",
                version = "0.1.0",
                description = "Publish an event once. The service signs it, delivers it to every subscribed endpoint, "
                        + "retries failures with backoff and keeps a log of every attempt."),
        security = @SecurityRequirement(name = "apiKey"))
@SecurityScheme(
        name = "apiKey",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        description = "An application API key (whk_...) for /api/v1/*, or the admin key for /api/v1/admin/*")
public class OpenApiConfig {
}
