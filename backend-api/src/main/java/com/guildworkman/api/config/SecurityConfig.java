package com.guildworkman.api.config;

import com.guildworkman.api.security.CustomUserDetailsService;
import com.guildworkman.api.security.JwtAuthenticationFilter;
import com.guildworkman.api.security.RestAccessDeniedHandler;
import com.guildworkman.api.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import static org.springframework.security.config.Customizer.withDefaults;

/**
 * Stateless JWT security with role-based method security.
 *
 * <ul>
 *   <li>Sessions are {@code STATELESS} — every request is authenticated from
 *       its bearer token by {@link JwtAuthenticationFilter}.</li>
 *   <li>{@code /api/v1/auth/**}, the OpenAPI endpoints, and the pre-existing
 *       public {@code client}/{@code skilledWorker} routes are open; everything
 *       else requires authentication, and {@code /api/v1/admin/**} requires the
 *       ADMIN role.</li>
 *   <li>{@code /api/v1/webhooks/**} is open to the internet but not
 *       unauthenticated: provider webhooks authenticate the payload with an
 *       HMAC signature instead of the caller with a token. See the comment on
 *       the entry in {@code PUBLIC_ENDPOINTS} and
 *       {@code PaystackWebhookController}.</li>
 *   <li>401/403 are rendered as JSON by {@link RestAuthenticationEntryPoint} /
 *       {@link RestAccessDeniedHandler} so error responses stay consistent.</li>
 * </ul>
 *
 * <p>{@code @EnableMethodSecurity} additionally enables {@code @PreAuthorize}
 * for finer-grained RBAC on individual handlers.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final RestAuthenticationEntryPoint authenticationEntryPoint;
    private final RestAccessDeniedHandler accessDeniedHandler;
    private final CustomUserDetailsService userDetailsService;

    // Only the credential-bearing auth actions are public. Identity endpoints
    // such as /api/v1/auth/me deliberately fall through to
    // `anyRequest().authenticated()` so an anonymous call gets a 401.
    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/logout",
            "/api/v1/client/**",
            "/api/v1/skilledWorker/**",
            // Booking mirrors the access level of the flow it replaces: the
            // one-step /api/v1/client/bookAppointment is already public, and a
            // booking calendar has to read a worker's taken slots before anyone
            // signs in. Tightening the booking surface means tightening the
            // client surface with it — a deliberate auth-scope change, not
            // something to slip in with the concurrency fix.
            "/api/v1/booking/**",
            // Worker discovery is the marketplace's front door: finding a worker
            // by location/skill has to work before anyone signs in, the same way
            // the booking calendar does. It only exposes the already-public
            // worker profile fields plus a materialised reputation score, and is
            // read-only. Same access level as /api/v1/skilledWorker/** by design.
            "/api/v1/discovery/**",
            "/api/v1/chain/events/**",
            // Paystack webhooks. Unauthenticated because the provider holds no
            // credential of ours and posts from a rotating IP range; the
            // HMAC-SHA512 signature over the raw body is the authentication,
            // checked before the payload is parsed or any state is touched
            // (PaystackSignatureVerifier). An unsigned or forged delivery gets
            // a 401 and mutates nothing.
            //
            // Note the path: /api/v1/webhooks/**, deliberately NOT under
            // /api/v1/payments. Every payment route requires a bearer token,
            // and keeping the public matcher on a separate prefix means no
            // future widening of this pattern can reach them by accident.
            "/api/v1/webhooks/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authz -> authz
                        .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * Authentication manager backed by our {@link CustomUserDetailsService} and
     * the application {@link PasswordEncoder} (defined in {@code MapperConfig}).
     * Used by the login flow to verify credentials.
     */
    @Bean
    public AuthenticationManager authenticationManager(PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return new ProviderManager(provider);
    }
}
