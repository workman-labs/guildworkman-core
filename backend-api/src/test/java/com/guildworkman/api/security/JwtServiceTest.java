package com.guildworkman.api.security;

import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.guildworkman.api.config.JwtProperties;
import com.guildworkman.api.data.constants.Role;
import com.guildworkman.api.data.models.UserAccount;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for {@link JwtService} — no Spring context, no database, so
 * they run locally without Postgres.
 */
class JwtServiceTest {

    private static final String SECRET = "unit-test-signing-secret-at-least-32-bytes-long";

    private JwtProperties props(Duration accessTtl) {
        JwtProperties p = new JwtProperties();
        p.setSecret(SECRET);
        p.setIssuer("guildworkman-api");
        p.setAccessTokenTtl(accessTtl);
        p.setRefreshTokenTtl(Duration.ofDays(7));
        return p;
    }

    private UserAccount account() {
        UserAccount a = new UserAccount();
        a.setEmail("alice@example.com");
        a.setPassword("irrelevant-hash");
        a.setRole(Role.SKILLED_WORKER);
        // id is normally DB-generated; set via reflection-free setter.
        a.setId(42L);
        return a;
    }

    @Test
    void generatesAndVerifiesTokenRoundTrip() {
        JwtService service = new JwtService(props(Duration.ofMinutes(15)));
        String token = service.generateAccessToken(account());

        DecodedJWT jwt = service.verify(token);
        assertThat(service.extractUserId(jwt)).isEqualTo(42L);
        assertThat(service.extractEmail(jwt)).isEqualTo("alice@example.com");
        assertThat(service.extractRole(jwt)).isEqualTo(Role.SKILLED_WORKER);
    }

    @Test
    void expiredTokenIsRejected() {
        // Negative TTL => token already expired at issue time.
        JwtService service = new JwtService(props(Duration.ofSeconds(-10)));
        String token = service.generateAccessToken(account());

        assertThatThrownBy(() -> service.verify(token))
                .isInstanceOf(JWTVerificationException.class);
    }

    @Test
    void tokenSignedWithDifferentSecretIsRejected() {
        JwtService issuer = new JwtService(props(Duration.ofMinutes(15)));
        String token = issuer.generateAccessToken(account());

        JwtProperties otherSecret = props(Duration.ofMinutes(15));
        otherSecret.setSecret("a-totally-different-secret-key-32-bytes-xx");
        JwtService verifier = new JwtService(otherSecret);

        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(JWTVerificationException.class);
    }

    @Test
    void tamperedTokenIsRejected() {
        JwtService service = new JwtService(props(Duration.ofMinutes(15)));
        String token = service.generateAccessToken(account());
        String tampered = token.substring(0, token.length() - 2) + "xx";

        assertThatThrownBy(() -> service.verify(tampered))
                .isInstanceOf(JWTVerificationException.class);
    }

    @Test
    void reportsAccessTtlInSeconds() {
        JwtService service = new JwtService(props(Duration.ofMinutes(15)));
        assertThat(service.accessTokenTtlSeconds()).isEqualTo(900);
    }
}
