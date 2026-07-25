package com.guildworkman.api.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guildworkman.api.data.constants.Role;
import com.guildworkman.api.data.models.RefreshToken;
import com.guildworkman.api.data.models.UserAccount;
import com.guildworkman.api.data.repository.RefreshTokenRepository;
import com.guildworkman.api.data.repository.UserAccountRepository;
import com.guildworkman.api.services.ServiceUtils.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end auth tests against the real Spring context and Postgres (provided
 * by the CI {@code test.yml} service). Covers the happy path, RBAC (403/200),
 * refresh rotation, reuse detection, logout, and concurrent refresh.
 *
 * <p>Deliberately NOT {@code @Transactional}: the concurrency test needs its
 * seed data committed so worker threads (separate connections) can see it, so
 * state is reset explicitly in {@link #cleanSlate()}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private UserAccountRepository userAccountRepository;
    @Autowired
    private RefreshTokenRepository refreshTokenRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private RefreshTokenService refreshTokenService;

    @BeforeEach
    void cleanSlate() {
        refreshTokenRepository.deleteAll();
        userAccountRepository.deleteAll();
    }

    // ----- helpers -----

    private String json(Map<String, ?> body) throws Exception {
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode dataOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
    }

    private JsonNode register(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isCreated())
                .andReturn();
        return dataOf(result);
    }

    private JsonNode login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return dataOf(result);
    }

    // ----- tests -----

    @Test
    void registerReturnsTokensAndProtectedEndpointRequiresThem() throws Exception {
        JsonNode data = register("alice@example.com", "Password123");
        String accessToken = data.get("accessToken").asText();
        assertThat(data.get("refreshToken").asText()).isNotBlank();
        assertThat(data.get("role").asText()).isEqualTo("CLIENT");

        // With a valid token, /me returns the caller's identity.
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("alice@example.com"))
                .andExpect(jsonPath("$.data.role").value("CLIENT"));

        // Without a token, it is rejected with 401.
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateEmailIsRejected() throws Exception {
        register("dup@example.com", "Password123");
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "dup@example.com", "password", "Password123"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Email already registered"));
    }

    @Test
    void invalidRegistrationIsRejectedWith400() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "not-an-email", "password", "short"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors").isMap());
    }

    @Test
    void loginWithWrongPasswordReturns401() throws Exception {
        register("bob@example.com", "Password123");
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "bob@example.com", "password", "WrongPass1"))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void selfRegistrationCannotBecomeAdmin() throws Exception {
        // Even asking for ADMIN yields a CLIENT — no privilege escalation.
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", "sneaky@example.com",
                                "password", "Password123", "role", "ADMIN"))))
                .andExpect(status().isCreated())
                .andReturn();
        assertThat(dataOf(result).get("role").asText()).isEqualTo("CLIENT");
    }

    @Test
    void rbacBlocksClientButAllowsAdmin() throws Exception {
        // A CLIENT is forbidden from the admin endpoint.
        JsonNode client = register("client@example.com", "Password123");
        mockMvc.perform(get("/api/v1/admin/ping")
                        .header("Authorization", "Bearer " + client.get("accessToken").asText()))
                .andExpect(status().isForbidden());

        // Seed an ADMIN out of band (admins are not self-registerable), then log in.
        UserAccount admin = new UserAccount();
        admin.setEmail("admin@example.com");
        admin.setPassword(passwordEncoder.encode("Password123"));
        admin.setRole(Role.ADMIN);
        admin.setEnabled(true);
        userAccountRepository.save(admin);

        JsonNode adminTokens = login("admin@example.com", "Password123");
        mockMvc.perform(get("/api/v1/admin/ping")
                        .header("Authorization", "Bearer " + adminTokens.get("accessToken").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.message").value("pong"));
    }

    @Test
    void refreshRotatesAndDetectsReuse() throws Exception {
        JsonNode tokens = register("rot@example.com", "Password123");
        String refresh1 = tokens.get("refreshToken").asText();

        // Rotate: refresh1 -> refresh2.
        MvcResult rotated = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", refresh1))))
                .andExpect(status().isOk())
                .andReturn();
        String refresh2 = dataOf(rotated).get("refreshToken").asText();
        assertThat(refresh2).isNotEqualTo(refresh1);

        // Replaying the now-rotated refresh1 is reuse -> 401.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", refresh1))))
                .andExpect(status().isUnauthorized());

        // Reuse revoked the whole family, so even the "good" refresh2 is dead now.
        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", refresh2))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void logoutRevokesTheRefreshToken() throws Exception {
        JsonNode tokens = register("out@example.com", "Password123");
        String refresh = tokens.get("refreshToken").asText();

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", refresh))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("refreshToken", refresh))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void concurrentRefreshOfSameTokenYieldsAtMostOneWinnerAndKillsTheFamily() throws Exception {
        UserAccount user = new UserAccount();
        user.setEmail("race@example.com");
        user.setPassword(passwordEncoder.encode("Password123"));
        user.setRole(Role.CLIENT);
        user.setEnabled(true);
        user = userAccountRepository.save(user);

        String raw = refreshTokenService.issueForNewFamily(user.getId());
        String familyId = refreshTokenRepository.findAll().get(0).getFamilyId();

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger successes = new AtomicInteger();
        AtomicInteger failures = new AtomicInteger();

        List<Callable<Void>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                try {
                    refreshTokenService.rotate(raw);
                    successes.incrementAndGet();
                } catch (RuntimeException expected) {
                    failures.incrementAndGet();
                }
                return null;
            });
        }
        for (Future<Void> f : pool.invokeAll(tasks)) {
            f.get();
        }
        pool.shutdown();

        // At most one thread can consume a given refresh token.
        assertThat(successes.get()).isLessThanOrEqualTo(1);
        assertThat(successes.get() + failures.get()).isEqualTo(threads);

        // The concurrent double-spend is treated as reuse, so the entire family
        // is revoked — no active refresh token survives the burst.
        long active = refreshTokenRepository.findAll().stream()
                .filter(t -> familyId.equals(t.getFamilyId()))
                .filter(RefreshToken::isActive)
                .count();
        assertThat(active).isZero();
    }
}
