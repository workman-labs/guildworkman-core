package com.guildworkman.api.services.ServiceUtils;

import com.guildworkman.api.dto.requests.AuthenticationRequest;
import com.guildworkman.api.dto.requests.RegisterRequest;
import com.guildworkman.api.dto.requests.TokenRefreshRequest;
import com.guildworkman.api.dto.responses.AuthenticationResponse;
import com.guildworkman.api.dto.responses.UserProfileResponse;

/** Orchestrates registration, login, token refresh, logout and identity lookup. */
public interface AuthService {

    AuthenticationResponse register(RegisterRequest request);

    AuthenticationResponse login(AuthenticationRequest request);

    AuthenticationResponse refresh(TokenRefreshRequest request);

    void logout(TokenRefreshRequest request);

    UserProfileResponse currentUser(String email);
}
