package com.guildworkman.api.escrow.api;

import com.guildworkman.api.escrow.service.EscrowOrchestrationService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/escrow/orchestrations")
@RequiredArgsConstructor
@SecurityRequirement(name = "bearerAuth")
public class EscrowOrchestrationController {

    private final EscrowOrchestrationService service;

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EscrowOrchestrationResponse submit(@Valid @RequestBody SubmitOrchestrationRequest request) {
        return EscrowOrchestrationResponse.from(service.submit(request));
    }

    @GetMapping("/{id}")
    public EscrowOrchestrationResponse get(@PathVariable Long id) {
        return EscrowOrchestrationResponse.from(service.get(id));
    }
}
