package com.guildworkman.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guildworkman.api.handler.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Renders 401s as RFC 7807 (application/problem+json), matching
 * {@link com.guildworkman.api.handler.GlobalExceptionHandler}, instead of
 * Spring Security's default empty body.
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(ProblemDetails.CONTENT_TYPE);
        objectMapper.writeValue(response.getWriter(), ProblemDetails.asMap(
                HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication required",
                "Authentication required or token is invalid"));
    }
}
