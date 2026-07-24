package com.guildworkman.api.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Binds the {@code security.jwt.*} properties. TTLs are ISO-8601 durations
 * (e.g. {@code PT15M}, {@code P7D}) and are bound to {@link Duration} by Spring.
 */
@Component
@ConfigurationProperties(prefix = "security.jwt")
@Getter
@Setter
public class JwtProperties {

    /** HMAC-256 signing secret (>= 32 bytes in real environments). */
    private String secret;

    /** {@code iss} claim on issued access tokens. */
    private String issuer = "guildworkman-api";

    /** Lifetime of an access token. */
    private Duration accessTokenTtl = Duration.ofMinutes(15);

    /** Lifetime of a refresh token. */
    private Duration refreshTokenTtl = Duration.ofDays(7);
}
