package com.acme.backendfreshsense.traceability.application;

import com.acme.backendfreshsense.traceability.domain.FoodLot;
import com.acme.backendfreshsense.traceability.domain.TraceEvidence;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.UUID;

public interface EvidenceCodec {
    int SCHEMA_VERSION = 1;
    DateTimeFormatter UTC_MICROSECONDS = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();

    record LotRegisteredSnapshot(String domain, int schemaVersion, String evidenceId,
                                 String businessEventId, String eventType, String lotId,
                                 String lotCode, String ownerId, String productId, String productName,
                                 String productCategory, String initialQuantity, String unit,
                                 String receivedAt, String expiresOn, String createdAt,
                                 String lotStatus, String source) {
        public static LotRegisteredSnapshot from(FoodLot lot, UUID eventId, String name, String category) {
            return new LotRegisteredSnapshot(
                    "freshsense.trace.lot-registered", SCHEMA_VERSION, lot.initialEvidenceId().toString(),
                    eventId.toString(), TraceEvidence.Type.LOT_REGISTERED.name(), lot.id().toString(),
                    lot.code(), Long.toString(lot.ownerId()), Long.toString(lot.productId()), name,
                    category, lot.initialQuantity().toPlainString(), lot.unit().name(),
                    format(lot.receivedAt()), lot.expiresOn() == null ? null : lot.expiresOn().toString(),
                    format(lot.createdAt()), lot.status().name(), TraceEvidence.Source.AUTHENTICATED_USER.name());
        }

        private static String format(Instant instant) {
            return UTC_MICROSECONDS.format(instant);
        }
    }

    String encode(LotRegisteredSnapshot snapshot);
    String encode(CriticalTemperatureSnapshot snapshot);
    LotRegisteredSnapshot decode(String snapshot);
    String sha256(String canonicalSnapshot);

    record CriticalTemperatureSnapshot(String domain, int schemaVersion, String evidenceId, String businessEventId,
                                       String eventType, String readingId, String deviceId, String lotId,
                                       String assignmentId, String temperatureC, String unit, String rule,
                                       String ruleVersion, String direction, String thresholdC, String measuredAt,
                                       String receivedAt, String source) {}
}
