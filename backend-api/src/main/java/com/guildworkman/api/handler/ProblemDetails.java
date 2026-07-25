package com.guildworkman.api.handler;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shared RFC 7807 (application/problem+json) helpers. {@link GlobalExceptionHandler}
 * uses {@link #of} for exceptions raised inside controller methods, where Spring MVC's
 * message converters serialize {@link ProblemDetail} directly. The security filter
 * chain (401/403) runs before the DispatcherServlet and can't reach an
 * {@code @ExceptionHandler}, so {@link RestAccessDeniedHandler} and
 * {@link RestAuthenticationEntryPoint} write JSON themselves via {@link #asMap} to
 * keep the same response shape.
 */
public final class ProblemDetails {

    public static final String CONTENT_TYPE = "application/problem+json";
    private static final String TYPE_BASE = "https://guildworkman.dev/problems/";

    private ProblemDetails() {
    }

    public static ProblemDetail of(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(title);
        problemDetail.setType(URI.create(TYPE_BASE + slug));
        problemDetail.setProperty("timestamp", Instant.now());
        return problemDetail;
    }

    public static Map<String, Object> asMap(HttpStatus status, String slug, String title, String detail) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", TYPE_BASE + slug);
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("timestamp", Instant.now().toString());
        return body;
    }
}
