package com.guildworkman.api.escrow.rpc;

/** Raised for any transport or JSON-RPC-level failure talking to Soroban RPC. */
public class SorobanRpcException extends RuntimeException {
    public SorobanRpcException(String message) {
        super(message);
    }

    public SorobanRpcException(String message, Throwable cause) {
        super(message, cause);
    }
}
