package com.guildworkman.api.signing.custody;

import org.junit.jupiter.api.Test;
import org.stellar.sdk.KeyPair;

import static org.assertj.core.api.Assertions.assertThat;

class SecretRedactorTest {

    private static String randomSeed() {
        return String.valueOf(KeyPair.random().getSecretSeed());
    }

    @Test
    void redactsASecretSeedAnywhereInTheText() {
        String seed = randomSeed();
        String redacted = SecretRedactor.redact("KMS rejected the key " + seed + " — retry later");

        assertThat(redacted).doesNotContain(seed).contains(SecretRedactor.MARKER);
    }

    @Test
    void redactsEverySeedInTheText() {
        String first = randomSeed();
        String second = randomSeed();

        String redacted = SecretRedactor.redact(first + " and " + second);

        assertThat(redacted).doesNotContain(first).doesNotContain(second);
        assertThat(redacted.split(java.util.regex.Pattern.quote(SecretRedactor.MARKER), -1)).hasSize(3);
    }

    /**
     * Account ids share the seed's alphabet and length. Redacting them would
     * blind operators to the identifiers they diagnose with, so the version
     * character has to be doing real work here.
     */
    @Test
    void leavesAccountIdsIntact() {
        String accountId = KeyPair.random().getAccountId();

        assertThat(SecretRedactor.redact("source account " + accountId))
                .isEqualTo("source account " + accountId);
        assertThat(SecretRedactor.containsSecret(accountId)).isFalse();
    }

    /**
     * Base64 XDR is a long run of the same alphabet and will contain an
     * interior {@code S} sooner or later; the lookarounds are what stop that
     * from being mistaken for a key and mangling a diagnostic blob.
     */
    @Test
    void doesNotMatchInsideALongerBase32Run() {
        String longRun = "S" + "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".repeat(4);

        assertThat(SecretRedactor.containsSecret(longRun)).isFalse();
        assertThat(SecretRedactor.redact(longRun)).isEqualTo(longRun);
    }

    @Test
    void passesThroughNullAndEmpty() {
        assertThat(SecretRedactor.redact(null)).isNull();
        assertThat(SecretRedactor.redact("")).isEmpty();
        assertThat(SecretRedactor.containsSecret(null)).isFalse();
    }
}
