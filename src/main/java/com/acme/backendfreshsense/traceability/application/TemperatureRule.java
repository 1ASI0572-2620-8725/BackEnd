package com.acme.backendfreshsense.traceability.application;

import java.math.BigDecimal;
import java.util.Optional;

final class TemperatureRule {
    static final String PROFILE = "FS-FRUIT-1";
    static final String VERSION = "1";
    private static final BigDecimal UPPER = new BigDecimal("12");
    private static final BigDecimal LOWER = new BigDecimal("-1");

    record Breach(String direction, BigDecimal threshold) {}

    private TemperatureRule() {}

    static boolean applies(String profile, String historicalCategory) {
        return PROFILE.equals(profile) && "Frutas".equalsIgnoreCase(historicalCategory);
    }

    static Optional<Breach> check(BigDecimal temperature) {
        if (temperature.compareTo(UPPER) > 0) return Optional.of(new Breach("ABOVE", UPPER));
        if (temperature.compareTo(LOWER) < 0) return Optional.of(new Breach("BELOW", LOWER));
        return Optional.empty();
    }
}
