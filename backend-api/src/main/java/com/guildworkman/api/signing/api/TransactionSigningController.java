package com.guildworkman.api.signing.api;

import com.guildworkman.api.signing.service.TransactionSubmissionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Server-side signing and submission of Stellar/Soroban transactions.
 *
 * <p>Submission is asynchronous by design and always answers {@code 202
 * Accepted}: signing needs a channel account, simulation needs a network
 * round-trip, and inclusion needs a ledger to close. Callers poll
 * {@code GET /{id}} (or look the submission up by their own
 * {@code reference}) for the outcome.
 *
 * <p>Resubmitting an {@code idempotencyKey} returns the original submission
 * with {@code X-Idempotent-Replay: true} rather than signing a second
 * transaction — the same contract the escrow orchestration endpoints use, and
 * for a sharper reason here: a duplicate would consume a second sequence
 * number and put a second transaction on-chain.
 *
 * <p>Authentication is required (the API's default), and no endpoint here
 * exposes an envelope's signatures or anything derived from key material.
 * See {@code docs/STELLAR_SIGNING.md}.
 */
@RestController
@RequestMapping("/api/v1/stellar/transactions")
@RequiredArgsConstructor
@Validated
@SecurityRequirement(name = "bearerAuth")
public class TransactionSigningController {

    /** {@code true}/{@code false} on the submit response; see the class Javadoc. */
    public static final String IDEMPOTENT_REPLAY_HEADER = "X-Idempotent-Replay";

    private final TransactionSubmissionService service;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Sign and submit a transaction",
            description = "Takes an unsigned envelope carrying the operations to execute. The service leases a "
                    + "channel account, sets the source account, sequence number, fee and time bounds, simulates "
                    + "(for Soroban invocations), signs with the configured custody backend and submits — "
                    + "fee-bumping the transaction if it stalls. Idempotent on idempotencyKey: a repeat returns the "
                    + "original submission, flagged by the X-Idempotent-Replay response header.")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Accepted (newly created, or replayed)"),
            @ApiResponse(responseCode = "400",
                    description = "Malformed envelope, unsupported preconditions, or an unknown key reference"),
            @ApiResponse(responseCode = "401", description = "Missing or invalid bearer token")
    })
    public ResponseEntity<TransactionSubmissionResponse> submit(@Valid @RequestBody SubmitTransactionRequest request) {
        var outcome = service.submit(request);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .header(IDEMPOTENT_REPLAY_HEADER, String.valueOf(outcome.replayed()))
                .body(TransactionSubmissionResponse.from(outcome.submission()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a submission's current state",
            description = "Current status, failure reason, transaction hash(es), fee and fee-bump count. "
                    + "Reports the current state, not a per-attempt audit log — individual attempts are in the "
                    + "application logs, correlated by submission id.")
    public TransactionSubmissionResponse get(@PathVariable Long id) {
        return TransactionSubmissionResponse.from(service.get(id));
    }

    @GetMapping
    @Operation(summary = "Find submissions by the caller's own reference",
            description = "Looks up submissions by the correlation handle supplied at submit time, for callers "
                    + "that would rather not store our submission ids.")
    public List<TransactionSubmissionResponse> findByReference(
            @RequestParam @NotBlank @Size(max = 128) String reference) {
        return service.findByReference(reference).stream()
                .map(TransactionSubmissionResponse::from)
                .toList();
    }
}
