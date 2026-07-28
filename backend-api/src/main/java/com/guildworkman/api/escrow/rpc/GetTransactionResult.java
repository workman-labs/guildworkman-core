package com.guildworkman.api.escrow.rpc;

/**
 * Result of a Soroban RPC {@code getTransaction} call.
 *
 * @param status     one of {@code SUCCESS}, {@code NOT_FOUND}, {@code FAILED}
 * @param resultXdr  base64 {@code TransactionResult} XDR when the transaction was found, else {@code null}
 * @param ledger     ledger sequence the transaction was included in, or {@code null} if not found
 */
public record GetTransactionResult(String status, String resultXdr, Long ledger) {

    public boolean isSuccess() {
        return "SUCCESS".equals(status);
    }

    public boolean isFailed() {
        return "FAILED".equals(status);
    }

    public boolean isNotFound() {
        return "NOT_FOUND".equals(status);
    }
}
