package com.guildworkman.api.booking.api;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ReserveSlotRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    private static Set<ConstraintViolation<ReserveSlotRequest>> validate(ReserveSlotRequest request) {
        return validator.validate(request);
    }

    private static ReserveSlotRequest valid() {
        return new ReserveSlotRequest("idem-1", 1L, 2L, LocalDateTime.now().plusDays(1), 60);
    }

    @Test
    void acceptsAWellFormedRequest() {
        assertThat(validate(valid())).isEmpty();
    }

    @Test
    void acceptsAnOmittedDuration() {
        ReserveSlotRequest request = new ReserveSlotRequest("idem-1", 1L, 2L,
                LocalDateTime.now().plusDays(1), null);
        assertThat(validate(request)).isEmpty();
    }

    @Test
    void rejectsABlankIdempotencyKey() {
        ReserveSlotRequest request = new ReserveSlotRequest("  ", 1L, 2L, LocalDateTime.now().plusDays(1), 60);
        assertThat(validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("idempotencyKey"));
    }

    @Test
    void rejectsAnOversizedIdempotencyKey() {
        ReserveSlotRequest request = new ReserveSlotRequest("k".repeat(129), 1L, 2L,
                LocalDateTime.now().plusDays(1), 60);
        assertThat(validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("idempotencyKey"));
    }

    @Test
    void rejectsASlotInThePast() {
        ReserveSlotRequest request = new ReserveSlotRequest("idem-1", 1L, 2L,
                LocalDateTime.now().minusMinutes(1), 60);
        assertThat(validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("slotStart"));
    }

    @Test
    void rejectsANonPositiveDuration() {
        ReserveSlotRequest request = new ReserveSlotRequest("idem-1", 1L, 2L,
                LocalDateTime.now().plusDays(1), 0);
        assertThat(validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("durationMinutes"));
    }

    @Test
    void rejectsAMissingWorkerOrClient() {
        ReserveSlotRequest request = new ReserveSlotRequest("idem-1", null, null,
                LocalDateTime.now().plusDays(1), 60);
        assertThat(validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("skilledWorkerId"));
        assertThat(validate(request)).anyMatch(v -> v.getPropertyPath().toString().equals("clientId"));
    }
}
