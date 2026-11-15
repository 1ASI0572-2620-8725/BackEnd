package com.acme.backendfreshsense.traceability.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

public record LotDeviceAssignment(UUID id, UUID lotId, long ownerId, long deviceKey,
                                  String deviceId, Instant startedAt, Instant endedAt) {

    public enum Status { ACTIVE, CLOSED }

    public LotDeviceAssignment {
        if (endedAt != null && !endedAt.isAfter(startedAt)) {
            throw new IllegalArgumentException("El cierre debe ser posterior al inicio");
        }
    }

    public Status status() {
        return endedAt == null ? Status.ACTIVE : Status.CLOSED;
    }

    public LotDeviceAssignment close(Instant now) {
        if (endedAt != null) {
            throw new IllegalStateException("La asociación ya está cerrada");
        }
        Instant end = now.isAfter(startedAt) ? now : startedAt.plus(1, ChronoUnit.MICROS);
        return new LotDeviceAssignment(id, lotId, ownerId, deviceKey, deviceId, startedAt, end);
    }
}
