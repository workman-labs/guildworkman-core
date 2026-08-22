package com.guildworkman.api.signing.api;

import com.guildworkman.api.signing.StellarTestFixtures;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.stellar.sdk.KeyPair;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Request-level validation for the signing endpoints. The load-bearing case is
 * the last one: a Stellar secret seed is 56 characters of letters and digits,
 * which is exactly the shape of a plausible key alias, so "references only"
 * has to be enforced rather than assumed — otherwise a fat-fingered paste ends
 * up in a database column and an access log.
 */
class SigningRequestValidationTest {

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

    private static SubmitTransactionRequest submit(String envelopeXdr, List<String> extraSigners) {
        return new SubmitTransactionRequest("idem-1", "appointment-42", envelopeXdr, extraSigners);
    }

    private static Set<String> invalidFields(Object request) {
        return validator.validate(request).stream()
                .map(ConstraintViolation::getPropertyPath)
                .map(Object::toString)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Test
    void acceptsARealEnvelopeWithNoExtraSigners() {
        assertThat(validator.validate(submit(StellarTestFixtures.unsignedEnvelope(), null))).isEmpty();
    }

    @Test
    void requiresAnIdempotencyKeyAndAnEnvelope() {
        assertThat(invalidFields(new SubmitTransactionRequest("  ", null, "", null)))
                .contains("idempotencyKey", "unsignedTransactionXdr");
    }

    @Test
    void rejectsANonBase64Envelope() {
        assertThat(invalidFields(submit("not base64!", null))).contains("unsignedTransactionXdr");
    }

    @Test
    void rejectsAnOversizedEnvelope() {
        assertThat(invalidFields(submit("A".repeat(65_537), null))).contains("unsignedTransactionXdr");
    }

    @Test
    void boundsTheNumberOfExtraSigners() {
        assertThat(invalidFields(submit(StellarTestFixtures.unsignedEnvelope(), List.of("a", "b", "c", "d"))))
                .contains("extraSignerKeyRefs");
    }

    @Test
    void rejectsASecretSeedPastedAsAnExtraSignerReference() {
        String seed = String.valueOf(KeyPair.random().getSecretSeed());

        Set<ConstraintViolation<SubmitTransactionRequest>> violations =
                validator.validate(submit(StellarTestFixtures.unsignedEnvelope(), List.of(seed)));

        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getMessage().contains("must not be a secret seed"));
    }

    @Test
    void rejectsASecretSeedPastedAsAChannelAccountReference() {
        String seed = String.valueOf(KeyPair.random().getSecretSeed());

        assertThat(validator.validate(new RegisterChannelAccountRequest(seed)))
                .anyMatch(v -> v.getMessage().contains("must not be a secret seed"));
    }

    @Test
    void acceptsAnOrdinaryChannelAccountReference() {
        assertThat(validator.validate(new RegisterChannelAccountRequest("channel-1"))).isEmpty();
    }

    /** An account id is public data but still isn't a key reference. */
    @Test
    void rejectsPunctuationAndWhitespaceInAKeyReference() {
        assertThat(invalidFields(new RegisterChannelAccountRequest("channel 1"))).contains("keyRef");
        assertThat(invalidFields(new RegisterChannelAccountRequest(""))).contains("keyRef");
    }
}
