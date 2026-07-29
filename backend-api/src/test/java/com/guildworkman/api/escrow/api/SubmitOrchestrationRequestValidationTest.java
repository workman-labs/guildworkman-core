package com.guildworkman.api.escrow.api;

import com.guildworkman.api.escrow.model.EscrowOperationType;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SubmitOrchestrationRequestValidationTest {

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

    private static SubmitOrchestrationRequest request(String signedTransactionXdr) {
        return new SubmitOrchestrationRequest(
                "idem-1", EscrowOperationType.CONFIRM_COMPLETION, "CABC", "42", signedTransactionXdr);
    }

    @Test
    void acceptsAValidBase64Envelope() {
        Set<ConstraintViolation<SubmitOrchestrationRequest>> violations = validator.validate(request("AAAA=="));
        assertThat(violations).isEmpty();
    }

    @Test
    void rejectsAnOversizedEnvelope() {
        String oversized = "A".repeat(8193);
        Set<ConstraintViolation<SubmitOrchestrationRequest>> violations = validator.validate(request(oversized));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("signedTransactionXdr"));
    }

    @Test
    void acceptsAnEnvelopeAtTheSizeLimit() {
        String atLimit = "A".repeat(8192);
        Set<ConstraintViolation<SubmitOrchestrationRequest>> violations = validator.validate(request(atLimit));
        assertThat(violations).isEmpty();
    }

    @Test
    void rejectsNonBase64Content() {
        Set<ConstraintViolation<SubmitOrchestrationRequest>> violations = validator.validate(request("not valid xdr!!"));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("signedTransactionXdr"));
    }

    @Test
    void rejectsBlankEnvelope() {
        Set<ConstraintViolation<SubmitOrchestrationRequest>> violations = validator.validate(request(""));
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("signedTransactionXdr"));
    }

    @Test
    void rejectsOperationRefContainingAQuote() {
        SubmitOrchestrationRequest req = new SubmitOrchestrationRequest(
                "idem-1", EscrowOperationType.CONFIRM_COMPLETION, "CABC", "4\"2", "AAAA==");
        Set<ConstraintViolation<SubmitOrchestrationRequest>> violations = validator.validate(req);
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("operationRef"));
    }
}
