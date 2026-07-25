package com.guildworkman.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guildworkman.api.handler.ProblemDetails;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Renders 403s (authenticated but lacking the required role) as RFC 7807
 * (application/problem+json), matching {@link com.guildworkman.api.handler.GlobalExceptionHandler}.
 */
@Component
@RequiredArgsConstructor
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(ProblemDetails.CONTENT_TYPE);
        objectMapper.writeValue(response.getWriter(), ProblemDetails.asMap(
                HttpStatus.FORBIDDEN, "access-denied", "Access denied",
                "You do not have permission to access this resource"));
    }
}
