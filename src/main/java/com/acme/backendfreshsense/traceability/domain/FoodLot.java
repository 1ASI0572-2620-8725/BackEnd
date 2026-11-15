package com.acme.backendfreshsense.traceability.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record FoodLot(UUID id, String code, long productId, long ownerId,
                      BigDecimal initialQuantity, Unit unit, Instant receivedAt,
                      LocalDate expiresOn, Instant createdAt, Status status, UUID initialEvidenceId) {
    private static final Instant FIRST_SUPPORTED_INSTANT = Instant.parse("1000-01-01T00:00:00Z");
    private static final Instant END_OF_SUPPORTED_RANGE = Instant.parse("+10000-01-01T00:00:00Z");

    public enum Unit { UNIT, KG, G, L, ML }
    public enum Status { REGISTERED }

    public FoodLot {
        code = normalizeCode(code);
        initialQuantity = normalizeQuantity(initialQuantity);
        Objects.requireNonNull(unit, "Unidad requerida");
        Objects.requireNonNull(receivedAt, "Fecha de ingreso requerida");
        if (receivedAt.isBefore(FIRST_SUPPORTED_INSTANT) || !receivedAt.isBefore(END_OF_SUPPORTED_RANGE)) {
            throw new IllegalArgumentException("La fecha de ingreso debe estar entre los años 1000 y 9999");
        }
        if (expiresOn != null && (expiresOn.isBefore(receivedAt.atOffset(ZoneOffset.UTC).toLocalDate())
                || expiresOn.getYear() > 9999)) {
            throw new IllegalArgumentException("El vencimiento debe ser igual o posterior al día UTC de ingreso y anterior al año 10000");
        }
    }

    private static String normalizeCode(String code) {
        String normalized = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("Código de lote inválido");
        }
        return normalized;
    }

    private static BigDecimal normalizeQuantity(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0 || quantity.compareTo(new BigDecimal("1000000000000")) >= 0) {
            throw new IllegalArgumentException("La cantidad debe ser positiva y tener hasta 12 dígitos enteros");
        }
        BigDecimal normalized = quantity.stripTrailingZeros();
        if (normalized.scale() > 3) {
            throw new IllegalArgumentException("La cantidad admite hasta tres decimales");
        }
        return normalized.setScale(3);
    }
}
