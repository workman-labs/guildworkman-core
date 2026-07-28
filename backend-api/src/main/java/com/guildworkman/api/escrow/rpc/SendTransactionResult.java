package com.guildworkman.api.escrow.rpc;

/**
 * Result of a Soroban RPC {@code sendTransaction} call.
 *
 * @param hash          the transaction hash (present for PENDING/DUPLICATE)
 * @param status        one of {@code PENDING}, {@code DUPLICATE}, {@code TRY_AGAIN_LATER}, {@code ERROR}
 * @param errorResultXdr base64 {@code TransactionResult} XDR when status is {@code ERROR}, else {@code null}
 */
public record SendTransactionResult(String hash, String status, String errorResultXdr) {

    public boolean isAccepted() {
        return "PENDING".equals(status) || "DUPLICATE".equals(status);
    }

    public boolean isRetryable() {
        return "TRY_AGAIN_LATER".equals(status);
    }
}
