package com.guildworkman.api.discovery.reputation;

/**
 * The reputation read-model could not be reached or returned something
 * unusable. Never surfaces to an API caller — {@link ReputationSnapshotService}
 * catches it, keeps any existing snapshot, and retries on the next tick.
 */
public class ReputationReadException extends RuntimeException {

    public ReputationReadException(String message) {
        super(message);
    }

    public ReputationReadException(String message, Throwable cause) {
        super(message, cause);
    }
}
