package com.guildworkman.api.controllers;

import com.guildworkman.api.dto.responses.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Example ADMIN-only surface. Access is enforced two ways that must both pass:
 * the URL rule in {@code SecurityConfig} ({@code /api/v1/admin/** → hasRole ADMIN})
 * and the method-level {@link PreAuthorize} below — demonstrating the RBAC wiring
 * end to end.
 */
@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Admin", description = "ADMIN-role-gated endpoints")
public class AdminController {

    @Operation(summary = "ADMIN-only health ping",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/ping")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse> ping(Authentication authentication) {
        return ResponseEntity.ok(new ApiResponse(
                Map.of("message", "pong", "caller", authentication.getName()), true));
    }
}
