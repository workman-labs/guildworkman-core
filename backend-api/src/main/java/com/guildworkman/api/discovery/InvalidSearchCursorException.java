package com.guildworkman.api.discovery;

/**
 * The {@code cursor} request parameter could not be decoded — malformed,
 * truncated, tampered with, or produced by a different cursor version. Mapped to
 * {@code 400 invalid-search-cursor} by {@code GlobalExceptionHandler} rather
 * than being silently ignored (which would restart pagination from the top and
 * silently repeat rows).
 */
public class InvalidSearchCursorException extends RuntimeException {

    public InvalidSearchCursorException(String message) {
        super(message);
    }

    public InvalidSearchCursorException(String message, Throwable cause) {
        super(message, cause);
    }
}
