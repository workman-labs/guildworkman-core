package com.guildworkman.api.controllers;

import com.guildworkman.api.dto.requests.AuthenticationRequest;
import com.guildworkman.api.dto.requests.RegisterRequest;
import com.guildworkman.api.dto.requests.TokenRefreshRequest;
import com.guildworkman.api.dto.responses.ApiResponse;
import com.guildworkman.api.services.ServiceUtils.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "JWT authentication, RBAC and refresh-token rotation")
public class AuthController {

    private final AuthService authService;

    @Operation(summary = "Register a new account and receive an initial token pair")
    @PostMapping("/register")
    public ResponseEntity<ApiResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse(authService.register(request), true));
    }

    @Operation(summary = "Authenticate with email/password and receive a token pair")
    @PostMapping("/login")
    public ResponseEntity<ApiResponse> login(@Valid @RequestBody AuthenticationRequest request) {
        return ResponseEntity.ok(new ApiResponse(authService.login(request), true));
    }

    @Operation(summary = "Rotate a refresh token for a new access + refresh token pair")
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse> refresh(@Valid @RequestBody TokenRefreshRequest request) {
        return ResponseEntity.ok(new ApiResponse(authService.refresh(request), true));
    }

    @Operation(summary = "Revoke the refresh token's whole family (logout)")
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse> logout(@Valid @RequestBody TokenRefreshRequest request) {
        authService.logout(request);
        return ResponseEntity.ok(new ApiResponse(Map.of("message", "Logged out"), true));
    }

    @Operation(summary = "Return the authenticated caller's identity",
            security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/me")
    public ResponseEntity<ApiResponse> me(Authentication authentication) {
        return ResponseEntity.ok(
                new ApiResponse(authService.currentUser(authentication.getName()), true));
    }
}
