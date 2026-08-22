package com.guildworkman.api.signing.api;

import com.guildworkman.api.signing.service.ChannelAccountLeaseService;
import com.guildworkman.api.signing.service.ChannelAccountService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Operator management of the channel-account pool.
 *
 * <p>The pool is what makes concurrent submissions possible at all: Stellar
 * requires each transaction to carry exactly the source account's next
 * sequence number, so two transactions signed for one account at the same time
 * are two transactions competing for one number. One account per in-flight
 * transaction sidesteps that, and this controller is how operators size and
 * repair that pool.
 *
 * <p>Every endpoint is ADMIN-only. Nothing here accepts or returns key
 * material — accounts are registered by key reference and resolved through
 * custody — but pool composition and lease state are still operational
 * information that ordinary callers have no business reading.
 */
@RestController
@RequestMapping("/api/v1/stellar/channel-accounts")
@RequiredArgsConstructor
@Validated
@PreAuthorize("hasRole('ADMIN')")
@SecurityRequirement(name = "bearerAuth")
public class ChannelAccountController {

    private final ChannelAccountService channelAccounts;
    private final ChannelAccountLeaseService leases;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Register a channel account",
            description = "ADMIN only. Adds the account behind a key reference to the pool; the account id is "
                    + "derived from the active signing provider, so a key this deployment cannot sign with "
                    + "cannot be registered. The account starts NEEDS_RESYNC — its sequence number is read from "
                    + "the network on first use, which keeps registration free of RPC dependencies.")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Registered"),
            @ApiResponse(responseCode = "400", description = "Key reference unknown to the signing provider"),
            @ApiResponse(responseCode = "409", description = "Key reference or account already registered")
    })
    public ChannelAccountResponse register(@Valid @RequestBody RegisterChannelAccountRequest request) {
        return ChannelAccountResponse.from(channelAccounts.register(request.keyRef()));
    }

    @GetMapping
    @Operation(summary = "List the pool",
            description = "ADMIN only. Every registered account with its status, next sequence number and "
                    + "current lease, if any. The primary pool-health view: a pool where every account is "
                    + "LEASED is a pool that is about to start rejecting submissions.")
    public List<ChannelAccountResponse> list() {
        return channelAccounts.list().stream().map(ChannelAccountResponse::from).toList();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch one channel account")
    @ApiResponses(@ApiResponse(responseCode = "404", description = "No such channel account"))
    public ChannelAccountResponse get(@PathVariable Long id) {
        return ChannelAccountResponse.from(channelAccounts.get(id));
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "Take an account out of the pool",
            description = "ADMIN only. Stops the account being leased for new submissions — e.g. because it is "
                    + "being drained of funds or rotated out. Refused with 409 while a lease is outstanding, "
                    + "since disabling an account mid-transaction would strand its sequence number.")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "No such channel account"),
            @ApiResponse(responseCode = "409", description = "Account is currently leased")
    })
    public ChannelAccountResponse disable(@PathVariable Long id) {
        return ChannelAccountResponse.from(channelAccounts.disable(id));
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "Return an account to the pool",
            description = "ADMIN only. Marks a disabled account NEEDS_RESYNC rather than AVAILABLE: however "
                    + "long it sat out, its sequence number has to be re-read from the network before the pool "
                    + "trusts it again.")
    @ApiResponses(@ApiResponse(responseCode = "404", description = "No such channel account"))
    public ChannelAccountResponse enable(@PathVariable Long id) {
        return ChannelAccountResponse.from(channelAccounts.enable(id));
    }

    @PostMapping("/{id}/resync")
    @Operation(summary = "Re-read an account's sequence number from the network",
            description = "ADMIN only. The manual escape hatch for a pool member whose cached sequence has "
                    + "drifted — typically because the account was also used by something outside this service. "
                    + "The service does this by itself after any failure that suggests drift; this endpoint is "
                    + "for the cases an operator spots first.")
    @ApiResponses({
            @ApiResponse(responseCode = "404", description = "No such channel account"),
            @ApiResponse(responseCode = "409", description = "Account is currently leased, or not funded on-chain")
    })
    public ChannelAccountResponse resync(@PathVariable Long id) {
        return ChannelAccountResponse.from(leases.resync(id));
    }
}
