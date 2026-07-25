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
 * keep the same response shape — both paths use the same ISO-8601 string
 * representation for {@code timestamp} so clients always get one type back.
 */
public final class ProblemDetails {

    public static final String CONTENT_TYPE = "application/problem+json";

    /**
     * Base URI for the {@code type} field. Overridable via the
     * {@code guildworkman.problem-details.type-base} property (see
     * {@link ProblemDetailsConfig}) so staging/prod can expose their own
     * canonical documentation host without a code change.
     */
    private static volatile String typeBase = "https://guildworkman.dev/problems/";

    private ProblemDetails() {
    }

    static void setTypeBase(String typeBase) {
        ProblemDetails.typeBase = typeBase.endsWith("/") ? typeBase : typeBase + "/";
    }

    public static ProblemDetail of(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(status, detail);
        problemDetail.setTitle(title);
        problemDetail.setType(URI.create(typeBase + slug));
        problemDetail.setProperty("timestamp", Instant.now().toString());
        return problemDetail;
    }

    public static Map<String, Object> asMap(HttpStatus status, String slug, String title, String detail) {
        return asMap(status, slug, title, detail, null);
    }

    /** Same shape as {@link #of}, plus an optional {@code errors} extension. */
    public static Map<String, Object> asMap(HttpStatus status, String slug, String title, String detail,
                                             Map<String, String> errors) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", typeBase + slug);
        body.put("title", title);
        body.put("status", status.value());
        body.put("detail", detail);
        body.put("timestamp", Instant.now().toString());
        if (errors != null) {
            body.put("errors", errors);
        }
        return body;
    }
}
