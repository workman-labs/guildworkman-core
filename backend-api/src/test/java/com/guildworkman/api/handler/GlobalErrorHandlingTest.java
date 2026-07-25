package com.guildworkman.api.handler;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end coverage for the RFC 7807 (application/problem+json) error
 * contract: bean-validation failures, malformed bodies, missing/mistyped
 * parameters, and unmapped routes all render the same {@code type/title/
 * status/detail} shape, via public (no-auth) {@code /api/v1/client/**} and
 * {@code /api/v1/skilledWorker/**} routes.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GlobalErrorHandlingTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void beanValidationFailureRendersProblemJsonWithFieldErrors() throws Exception {
        // BookAppointmentRequest requires clientId, scheduleTime and category — send none.
        mockMvc.perform(post("/api/v1/client/bookAppointment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(ProblemDetails.CONTENT_TYPE))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.clientId").exists())
                .andExpect(jsonPath("$.errors.scheduleTime").exists())
                .andExpect(jsonPath("$.errors.category").exists());
    }

    @Test
    void missingRequiredParameterRendersProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/client/viewAllAppointment"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(ProblemDetails.CONTENT_TYPE))
                .andExpect(jsonPath("$.title").value("Missing request parameter"));
    }

    @Test
    void mistypedParameterRendersProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/skilledWorker/findById").param("skilledWorkerId", "not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(ProblemDetails.CONTENT_TYPE))
                .andExpect(jsonPath("$.title").value("Invalid parameter type"));
    }

    @Test
    void malformedJsonBodyRendersProblemJson() throws Exception {
        mockMvc.perform(post("/api/v1/client/bookAppointment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(ProblemDetails.CONTENT_TYPE))
                .andExpect(jsonPath("$.title").value("Malformed request body"));
    }

    @Test
    void unmappedRouteRendersProblemJson() throws Exception {
        // Under the public /api/v1/client/** prefix so the request reaches the
        // DispatcherServlet (and NoResourceFoundException) instead of being
        // rejected earlier as 401 by the security filter chain.
        mockMvc.perform(get("/api/v1/client/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(ProblemDetails.CONTENT_TYPE));
    }
}
