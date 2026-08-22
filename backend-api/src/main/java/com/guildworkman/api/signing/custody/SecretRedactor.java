package com.guildworkman.api.signing.custody;

import java.util.regex.Pattern;

/**
 * Last line of defence for the "no key material in logs, responses or
 * exception messages" rule.
 *
 * <p>Nothing in this package deliberately puts a secret seed into a string —
 * the {@link SigningProvider} contract has no way to hand one out, and
 * {@link LocalSigningProvider} keeps seeds inside {@code KeyPair} instances it
 * never exposes. This class exists for the paths we don't fully control:
 * error text coming back from a KMS, an exception message from a third-party
 * library that echoed its input, an operator pasting a seed into a request
 * field it doesn't belong in. Every string this feature persists to
 * {@code last_error} or writes to a log passes through {@link #redact} first,
 * so a mistake anywhere upstream degrades to a redaction marker instead of a
 * leaked key.
 *
 * <p>A Stellar Ed25519 secret seed is a 56-character base32 strkey beginning
 * with {@code S}. Account ids ({@code G…}), contract ids ({@code C…}) and
 * muxed accounts ({@code M…}) share the alphabet but not the version
 * character, so they're deliberately left intact — redacting them would blind
 * operators to exactly the identifiers they need for diagnosis.
 */
public final class SecretRedactor {

    public static final String MARKER = "S***REDACTED***";

    /**
     * The lookarounds stop a longer base32 run (a chunk of XDR, say) from
     * matching on a coincidental interior {@code S}: only a full-width strkey
     * standing on its own token boundary is treated as a seed.
     */
    private static final Pattern SECRET_SEED = Pattern.compile("(?<![A-Z2-7])S[A-Z2-7]{55}(?![A-Z2-7])");

    private SecretRedactor() {
    }

    /** @return {@code value} with any Stellar secret seed replaced by {@link #MARKER}; {@code null} in, {@code null} out. */
    public static String redact(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return SECRET_SEED.matcher(value).replaceAll(MARKER);
    }

    /** @return true if {@code value} contains something shaped like a Stellar secret seed. */
    public static boolean containsSecret(String value) {
        return value != null && SECRET_SEED.matcher(value).find();
    }
}
