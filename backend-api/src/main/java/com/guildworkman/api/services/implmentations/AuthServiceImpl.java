package com.guildworkman.api.services.implmentations;

import com.guildworkman.api.data.constants.Role;
import com.guildworkman.api.data.models.UserAccount;
import com.guildworkman.api.data.repository.UserAccountRepository;
import com.guildworkman.api.dto.requests.AuthenticationRequest;
import com.guildworkman.api.dto.requests.RegisterRequest;
import com.guildworkman.api.dto.requests.TokenRefreshRequest;
import com.guildworkman.api.dto.responses.AuthenticationResponse;
import com.guildworkman.api.dto.responses.UserProfileResponse;
import com.guildworkman.api.exceptions.EmailAlreadyExistsException;
import com.guildworkman.api.exceptions.TokenRefreshException;
import com.guildworkman.api.exceptions.UserNotFoundException;
import com.guildworkman.api.security.JwtService;
import com.guildworkman.api.services.ServiceUtils.AuthService;
import com.guildworkman.api.services.ServiceUtils.RefreshTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserAccountRepository userAccountRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;

    @Override
    public AuthenticationResponse register(RegisterRequest request) {
        String email = normalize(request.getEmail());
        if (userAccountRepository.existsByEmailIgnoreCase(email)) {
            throw new EmailAlreadyExistsException("An account with this email already exists");
        }

        UserAccount account = new UserAccount();
        account.setEmail(email);
        account.setPassword(passwordEncoder.encode(request.getPassword()));
        account.setRole(resolveSelfRegisterRole(request.getRole()));
        account.setEnabled(true);
        UserAccount saved = userAccountRepository.save(account);

        return issueTokens(saved);
    }

    @Override
    public AuthenticationResponse login(AuthenticationRequest request) {
        String email = normalize(request.getEmail());
        // Throws AuthenticationException (→ 401) on bad credentials.
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, request.getPassword()));

        UserAccount account = userAccountRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new UserNotFoundException("Account not found"));
        return issueTokens(account);
    }

    @Override
    public AuthenticationResponse refresh(TokenRefreshRequest request) {
        RefreshTokenService.RotationResult result =
                refreshTokenService.rotate(request.getRefreshToken());

        UserAccount account = userAccountRepository.findById(result.userAccountId())
                .orElseThrow(() -> new TokenRefreshException("Account no longer exists"));

        String accessToken = jwtService.generateAccessToken(account);
        return buildResponse(account, accessToken, result.newRefreshToken());
    }

    @Override
    public void logout(TokenRefreshRequest request) {
        refreshTokenService.revoke(request.getRefreshToken());
    }

    @Override
    public UserProfileResponse currentUser(String email) {
        UserAccount account = userAccountRepository.findByEmailIgnoreCase(normalize(email))
                .orElseThrow(() -> new UserNotFoundException("Account not found"));
        return new UserProfileResponse(account.getId(), account.getEmail(), account.getRole());
    }

    private AuthenticationResponse issueTokens(UserAccount account) {
        String accessToken = jwtService.generateAccessToken(account);
        String refreshToken = refreshTokenService.issueForNewFamily(account.getId());
        return buildResponse(account, accessToken, refreshToken);
    }

    private AuthenticationResponse buildResponse(UserAccount account, String accessToken, String refreshToken) {
        return AuthenticationResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .tokenType("Bearer")
                .expiresIn(jwtService.accessTokenTtlSeconds())
                .userId(account.getId())
                .email(account.getEmail())
                .role(account.getRole())
                .build();
    }

    /**
     * Self-registration may only create CLIENT or SKILLED_WORKER accounts.
     * ADMIN (or a null/unknown role) is never granted this way — that would be a
     * privilege-escalation hole. Admins are provisioned out of band.
     */
    private Role resolveSelfRegisterRole(Role requested) {
        return requested == Role.SKILLED_WORKER ? Role.SKILLED_WORKER : Role.CLIENT;
    }

    private String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase();
    }
}
