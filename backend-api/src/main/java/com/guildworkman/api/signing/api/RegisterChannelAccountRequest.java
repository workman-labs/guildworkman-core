package com.guildworkman.api.signing.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Adds one account to the channel-account pool.
 *
 * <p>Only a key <em>reference</em> is accepted. The account id is derived from
 * custody, so the pool can't contain an account this deployment is unable to
 * sign for, and no request to this API ever needs to carry key material.
 *
 * <p>A secret seed happens to fit the shape of an alias — 56 characters, no
 * punctuation — so "aliases only" is enforced rather than assumed: the second
 * pattern rejects anything shaped like a Stellar seed outright, turning a
 * fat-fingered paste into a {@code 400} instead of a secret sitting in a
 * database column and an access log.
 *
 * @param keyRef alias the active {@code SigningProvider} resolves to a signing key
 */
public record RegisterChannelAccountRequest(
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "[A-Za-z0-9._-]+", message = "must be a key reference, not key material")
        @Pattern(regexp = "^(?!S[A-Z2-7]{55}$).*$", message = "must not be a secret seed")
        String keyRef) {
}
