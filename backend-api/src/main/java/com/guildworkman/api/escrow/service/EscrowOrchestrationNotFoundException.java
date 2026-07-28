package com.guildworkman.api.escrow.service;

public class EscrowOrchestrationNotFoundException extends RuntimeException {
    public EscrowOrchestrationNotFoundException(Long id) {
        super("Escrow orchestration request not found: " + id);
    }
}
