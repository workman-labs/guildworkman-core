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

    // --- fuzzing the seed guard --------------------------------------------

    /**
     * The two tests above prove the seed pattern rejects <em>a</em> seed. This
     * proves it rejects <em>every</em> seed: a thousand freshly generated ones,
     * each a different 56-character base32 string. The pattern is a negative
     * lookahead over a character class, which is exactly the kind of expression
     * that works on the example it was written against and then lets some
     * character through — {@code [A-Z2-7]} excludes the digits 0, 1, 8 and 9,
     * so a single unlucky seed would be enough to punch a hole in it.
     *
     * <p>Real key pairs rather than synthesised strings, deliberately: the
     * question is whether the guard matches what the SDK actually produces, not
     * whether it matches what this test thinks strkey looks like.
     */
    @Test
    void everySecretSeedTheSdkCanProduceIsRejected() {
        for (int i = 0; i < 1_000; i++) {
            String seed = String.valueOf(KeyPair.random().getSecretSeed());

            assertThat(validator.validate(new RegisterChannelAccountRequest(seed)))
                    .withFailMessage("a generated secret seed passed validation at iteration %d "
                            + "(length %d) — the seed pattern has a hole in it", i, seed.length())
                    .anyMatch(v -> v.getMessage().contains("must not be a secret seed"));
        }
    }

    /**
     * The mirror image: the guard must not be so broad that it rejects the
     * aliases operators will actually use. Randomised over the alias character
     * set, with a fixed seed so a failure is reproducible from the message
     * rather than only on the machine that saw it.
     */
    @Test
    void ordinaryAliasesSurviveTheSeedGuard() {
        java.util.Random random = new java.util.Random(20260822L);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789._-";

        for (int i = 0; i < 1_000; i++) {
            // Includes length 56 — an alias is allowed to be seed-*length*,
            // it just isn't allowed to be seed-*shaped*.
            int length = 1 + random.nextInt(64);
            StringBuilder alias = new StringBuilder(length);
            for (int c = 0; c < length; c++) {
                alias.append(alphabet.charAt(random.nextInt(alphabet.length())));
            }

            assertThat(validator.validate(new RegisterChannelAccountRequest(alias.toString())))
                    .withFailMessage("a legitimate alias was rejected: '%s'", alias)
                    .isEmpty();
        }
    }

    /**
     * Whatever arrives, validation has to answer rather than throw — a
     * {@code RuntimeException} escaping a constraint validator becomes a 500
     * on a request that should have been a 400.
     */
    @Test
    void hostileInputIsAnswered_notThrownOn() {
        List<String> hostile = List.of(
                "\u0000nul", "\uD83D\uDD11", "../../etc/passwd",
                "'; DROP TABLE stellar_channel_accounts; --",
                "S".repeat(56), "S" + "A".repeat(55), "\n", "\t\t", "%s%n",
                "channel\u200B1", "S23456789012345678901234567890123456789012345678901234567",
                "\uFDD0", "a".repeat(10_000));

        for (String candidate : hostile) {
            assertThat(validator.validate(new RegisterChannelAccountRequest(candidate)))
                    .withFailMessage("validation produced no verdict for %s", candidate)
                    .isNotNull();
            assertThat(validator.validate(submit(StellarTestFixtures.unsignedEnvelope(), List.of(candidate))))
                    .isNotNull();
        }

        // "SAAA…" is a well-formed strkey shape even though it decodes to
        // nothing useful, so the guard must still refuse it.
        assertThat(validator.validate(new RegisterChannelAccountRequest("S" + "A".repeat(55))))
                .anyMatch(v -> v.getMessage().contains("must not be a secret seed"));
    }
}
