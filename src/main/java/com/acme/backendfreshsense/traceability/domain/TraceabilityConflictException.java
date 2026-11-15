package com.acme.backendfreshsense.traceability.domain;

public class TraceabilityConflictException extends RuntimeException {
    public TraceabilityConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
