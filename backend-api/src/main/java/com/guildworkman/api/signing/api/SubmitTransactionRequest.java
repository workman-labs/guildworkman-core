package com.guildworkman.api.signing.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Submits one transaction for server-side signing and submission.
 *
 * <p>The envelope carries <em>operations only</em>: this service replaces its
 * source account, sequence number, fee and time bounds when it builds the
 * real transaction on a leased channel account (see
 * {@code TransactionAssembler}). Callers therefore don't need to know which
 * account will send it, don't need a sequence number, and — the point of the
 * whole feature — don't need a key.
 *
 * @param idempotencyKey     caller-generated; resubmitting it returns the original submission rather than
 *                           signing a second transaction
 * @param reference          caller's own correlation handle (appointment id, escrow id, …); optional
 * @param unsignedTransactionXdr base64 {@code TransactionEnvelope} XDR holding the operations to execute
 * @param extraSignerKeyRefs key <em>references</em> (never keys) that must co-sign alongside the channel
 *                           account, e.g. a contract-admin key authorising the call
 */
public record SubmitTransactionRequest(

        @NotBlank @Size(max = 128) String idempotencyKey,

        @Size(max = 128) String reference,

        // 65536 chars accommodates a Soroban invocation with a large footprint
        // (well beyond the ~2KB a typical invoke encodes to) while still
        // bounding the request against an oversized or malicious payload.
        @NotBlank @Size(max = 65536)
        @Pattern(regexp = "^[A-Za-z0-9+/]+={0,2}$", message = "must be base64-encoded")
        String unsignedTransactionXdr,

        // Three is a deliberate ceiling: each extra signer is another custody
        // round-trip on the critical path of a database transaction holding a
        // channel-account lease.
        @Size(max = 3, message = "at most 3 extra signers are supported")
        List<@NotBlank @Size(max = 64)
             @Pattern(regexp = "[A-Za-z0-9._-]+", message = "must be a key reference, not key material")
             @Pattern(regexp = "^(?!S[A-Z2-7]{55}$).*$", message = "must not be a secret seed")
             String> extraSignerKeyRefs) {

    /** @return the extra signer references, never {@code null}. */
    public List<String> extraSignerKeyRefsOrEmpty() {
        return extraSignerKeyRefs == null ? List.of() : extraSignerKeyRefs;
    }
}
