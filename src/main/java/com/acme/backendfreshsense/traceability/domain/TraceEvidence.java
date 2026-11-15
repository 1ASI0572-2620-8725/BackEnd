package com.acme.backendfreshsense.traceability.domain;

import java.time.Instant;
import java.util.UUID;

public record TraceEvidence(UUID id, UUID lotId, UUID businessEventId, Type type,
                            int schemaVersion, String canonicalSnapshot, String sha256,
                            Instant createdAt, Source source) {
    public enum Type { LOT_REGISTERED }
    public enum Source { AUTHENTICATED_USER }
}
