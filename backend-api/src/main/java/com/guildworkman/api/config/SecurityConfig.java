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
