package com.guildworkman.api.handler;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Wires the configurable {@code type} base URI into {@link ProblemDetails}, a
 * static utility used from both {@code @ExceptionHandler} methods and the
 * (non-Spring-managed-call-site) security filter-chain handlers.
 */
@Component
public class ProblemDetailsConfig {

    @Value("${guildworkman.problem-details.type-base:https://guildworkman.dev/problems/}")
    private String typeBase;

    @PostConstruct
    void configure() {
        ProblemDetails.setTypeBase(typeBase);
    }
}
