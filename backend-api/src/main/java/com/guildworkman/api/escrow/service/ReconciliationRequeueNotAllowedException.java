package com.guildworkman.api.escrow.service;

import com.guildworkman.api.escrow.model.ReconciliationStatus;

/** Only a {@link ReconciliationStatus#MISMATCHED} request can be requeued. */
public class ReconciliationRequeueNotAllowedException extends RuntimeException {
    public ReconciliationRequeueNotAllowedException(Long id, ReconciliationStatus current) {
        super("Escrow orchestration request " + id + " is not MISMATCHED (current: " + current
                + "); only MISMATCHED requests can be requeued for reconciliation");
    }
}
