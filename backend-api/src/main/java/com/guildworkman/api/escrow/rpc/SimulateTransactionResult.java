package com.guildworkman.api.escrow.rpc;

/**
 * Result of a Soroban RPC {@code simulateTransaction} call — the dry run that
 * tells us what a contract invocation will cost and touch before anything is
 * signed or broadcast.
 *
 * @param error            simulation error text, or {@code null} when the simulation succeeded
 * @param minResourceFee   resource fee (stroops) the transaction must carry on top of its inclusion fee, or {@code null}
 * @param transactionData  base64 {@code SorobanTransactionData} XDR (the footprint) to attach to the transaction
 * @param requiresRestore  true when the RPC returned a {@code restorePreamble}: archived ledger entries must be
 *                         restored before this invocation can succeed, so submitting it as-is would only waste a fee
 */
public record SimulateTransactionResult(String error, Long minResourceFee, String transactionData,
                                        boolean requiresRestore) {

    public boolean isSuccess() {
        return error == null && !requiresRestore;
    }
}
