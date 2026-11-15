package com.acme.backendfreshsense.monitoring.application.service;

import com.acme.backendfreshsense.monitoring.infrastructure.persistence.jpa.SensorObservation.TimeQuality;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.time.temporal.ChronoUnit;

import static com.acme.backendfreshsense.monitoring.infrastructure.persistence.jpa.SensorObservation.TimeQuality.*;

record MeasurementTime(Instant measuredAt, TimeQuality quality) {
    private static final Duration MAX_AGE = Duration.ofHours(24);
    private static final Instant MIN_STORABLE = Instant.parse("1000-01-02T00:00:00Z");
    private static final Instant MAX_STORABLE = Instant.parse("9999-12-30T00:00:00Z");
    private static final DateTimeFormatter LEGACY_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final DateTimeFormatter LEGACY_STRICT = DateTimeFormatter.ofPattern("dd/MM/uuuu HH:mm")
            .withResolverStyle(ResolverStyle.STRICT);

    static MeasurementTime legacy(String raw) {
        if (raw == null || raw.isBlank()) return new MeasurementTime(null, MISSING);
        try {
            LocalDateTime.parse(raw.trim(), LEGACY_STRICT);
            return new MeasurementTime(null, UNSYNCHRONIZED);
        } catch (DateTimeParseException e) {
            return new MeasurementTime(null, INVALID);
        }
    }

    static LocalDateTime legacyDisplayTime(String raw) {
        if (raw == null || raw.isBlank()) return LocalDateTime.now();
        try {
            return LocalDateTime.parse(raw.trim(), LEGACY_TIME);
        } catch (DateTimeParseException e) {
            return LocalDateTime.now();
        }
    }

    static MeasurementTime interpret(String raw, ZoneId zone, boolean synchronizedClock, Instant received) {
        if (raw == null || raw.isBlank()) return new MeasurementTime(null, MISSING);
        Instant parsed;
        try {
            parsed = OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException missingOffset) {
            try {
                var local = LocalDateTime.parse(raw);
                if (zone == null) return new MeasurementTime(null, NO_ZONE);
                var validOffsets = zone.getRules().getValidOffsets(local);
                if (validOffsets.size() != 1) return new MeasurementTime(null, INVALID);
                parsed = local.toInstant(validOffsets.get(0));
            } catch (DateTimeParseException invalid) {
                return new MeasurementTime(null, INVALID);
            }
        }
        var measured = parsed.truncatedTo(ChronoUnit.MICROS);
        if (measured.isBefore(MIN_STORABLE) || measured.isAfter(MAX_STORABLE)) return new MeasurementTime(null, INVALID);
        if (measured.isAfter(received)) return new MeasurementTime(measured, FUTURE);
        if (measured.isBefore(received.minus(MAX_AGE))) return new MeasurementTime(measured, STALE);
        return new MeasurementTime(measured, synchronizedClock ? TRUSTED : UNSYNCHRONIZED);
    }
}
