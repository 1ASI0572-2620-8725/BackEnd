package com.acme.backendfreshsense.traceability.domain;

import java.time.Instant;
import java.util.UUID;

public record TraceEvidence(UUID id, UUID lotId, UUID businessEventId, Type type,
                            int schemaVersion, String canonicalSnapshot, String sha256,
                            Instant createdAt, Source source, Long readingId) {
    public TraceEvidence(UUID id, UUID lotId, UUID businessEventId, Type type, int schemaVersion,
                         String canonicalSnapshot, String sha256, Instant createdAt, Source source) {
        this(id, lotId, businessEventId, type, schemaVersion, canonicalSnapshot, sha256, createdAt, source, null);
    }

    public enum Type { LOT_REGISTERED, CRITICAL_TEMPERATURE }
    public enum Source { AUTHENTICATED_USER, AUTHENTICATED_GATEWAY, SIMULATED }
}
