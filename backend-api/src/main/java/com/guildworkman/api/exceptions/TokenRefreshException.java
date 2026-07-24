package com.guildworkman.api.exceptions;

/**
 * Thrown when a refresh token cannot be honoured — unknown, expired, already
 * rotated, or detected as reused. All map to HTTP 401: the client must
 * re-authenticate.
 */
public class TokenRefreshException extends RuntimeException {
    public TokenRefreshException(String message) {
        super(message);
    }
}
