package com.guildworkman.api.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata and the {@code bearerAuth} scheme referenced by protected
 * endpoints, so Swagger UI renders an "Authorize" button that sends
 * {@code Authorization: Bearer <access-token>}.
 */
@Configuration
@OpenAPIDefinition(info = @Info(
        title = "GuildWorkman API",
        version = "v1",
        description = "Backend API. Auth uses JWT access tokens with rotating refresh tokens."))
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
public class OpenApiConfig {
}
