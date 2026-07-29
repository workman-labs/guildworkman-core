package com.guildworkman.api.escrow.api;

import com.guildworkman.api.escrow.service.EscrowOrchestrationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * See {@code docs/ESCROW_ORCHESTRATION.md} for the full HTTP-behavior write-up.
 * In short: {@code POST} always returns {@code 202 Accepted} — for a request
 * whose {@code idempotencyKey} already exists, the body is the pre-existing
 * request's current state (not a fresh copy), signalled by the
 * {@code X-Idempotent-Replay} response header rather than a different status
 * code, since a replay is not an error condition.
 */
@RestController
@RequestMapping("/api/v1/escrow/orchestrations")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class EscrowOrchestrationController {

    /** Set to {@code true}/{@code false} on the submit response; see class Javadoc. */
    public static final String IDEMPOTENT_REPLAY_HEADER = "X-Idempotent-Replay";

    private final EscrowOrchestrationService service;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Submit a signed escrow-contract transaction for orchestration",
            description = "Idempotent on idempotencyKey: resubmitting the same key returns the "
                    + "original request (see the X-Idempotent-Replay response header) rather than "
                    + "creating a second one. Always 202 Accepted, whether newly created or replayed.")
    public ResponseEntity<EscrowOrchestrationResponse> submit(@Valid @RequestBody SubmitOrchestrationRequest request) {
        var outcome = service.submit(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(IDEMPOTENT_REPLAY_HEADER, String.valueOf(outcome.replayed()))
                .body(EscrowOrchestrationResponse.from(outcome.request()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a request's current status",
            description = "Returns the request's current-state timestamps (submittedAt/confirmedAt/reconciledAt) "
                    + "and attempt count. It is not a per-attempt audit log — individual submit/poll attempts "
                    + "are only recorded in application logs, correlated by orchestration request id.")
    public EscrowOrchestrationResponse get(@PathVariable Long id) {
        return EscrowOrchestrationResponse.from(service.get(id));
    }

    @PostMapping("/{id}/requeue-reconciliation")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Requeue a MISMATCHED request for reconciliation",
            description = "ADMIN only. Resets reconciliationStatus back to PENDING so the next reconciliation "
                    + "sweep reconsiders it -- e.g. after confirming the on-chain event the sweep was missing "
                    + "has since been ingested. 409 if the request isn't currently MISMATCHED.")
    public EscrowOrchestrationResponse requeueReconciliation(@PathVariable Long id) {
        return EscrowOrchestrationResponse.from(service.requeueReconciliation(id));
    }
}
