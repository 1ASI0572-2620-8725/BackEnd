package com.acme.backendfreshsense.monitoring.application.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pruebas unitarias del contrato de ingesta Edge (TS41):
 * deviceId, temperature, humidity y ethylenePpm son obligatorios.
 */
class EdgeReadingRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("Acepta una lectura Edge completa (temp, humedad, etileno)")
    void acceptsCompleteEdgeReading() {
        EdgeReadingRequest request = new EdgeReadingRequest(
                "esp32-freshsense-1",
                6.4,
                92.0,
                0.5,
                "28/10/2026 23:40",
                "reading-001",
                "GREEN",
                "Frutas"
        );

        Set<ConstraintViolation<EdgeReadingRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("Rechaza deviceId vacío")
    void rejectsBlankDeviceId() {
        EdgeReadingRequest request = new EdgeReadingRequest(
                "  ",
                6.4,
                92.0,
                0.5,
                "28/10/2026 23:40",
                "reading-001",
                "GREEN",
                "Frutas"
        );

        Set<ConstraintViolation<EdgeReadingRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("Rechaza temperatura nula")
    void rejectsNullTemperature() {
        EdgeReadingRequest request = new EdgeReadingRequest(
                "esp32-freshsense-1",
                null,
                92.0,
                0.5,
                "28/10/2026 23:40",
                "reading-001",
                "GREEN",
                "Frutas"
        );

        Set<ConstraintViolation<EdgeReadingRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("Rechaza etileno nulo (TS41 / US06)")
    void rejectsNullEthylene() {
        EdgeReadingRequest request = new EdgeReadingRequest(
                "esp32-freshsense-1",
                6.4,
                92.0,
                null,
                "28/10/2026 23:40",
                "reading-001",
                "GREEN",
                "Frutas"
        );

        Set<ConstraintViolation<EdgeReadingRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("Rechaza humedad nula")
    void rejectsNullHumidity() {
        EdgeReadingRequest request = new EdgeReadingRequest(
                "esp32-freshsense-1",
                6.4,
                null,
                0.5,
                "28/10/2026 23:40",
                "reading-001",
                "GREEN",
                "Frutas"
        );

        Set<ConstraintViolation<EdgeReadingRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("Rechaza deviceId nulo")
    void rejectsNullDeviceId() {
        EdgeReadingRequest request = new EdgeReadingRequest(
                null,
                6.4,
                92.0,
                0.5,
                "28/10/2026 23:40",
                "reading-001",
                "GREEN",
                "Frutas"
        );

        Set<ConstraintViolation<EdgeReadingRequest>> violations = validator.validate(request);
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("Acepta lectura sin status ni category (opcionales)")
    void acceptsReadingWithoutOptionalStatusAndCategory() {
        EdgeReadingRequest request = new EdgeReadingRequest(
                "esp32-freshsense-1",
                6.4,
                92.0,
                0.5,
                "28/10/2026 23:40",
                "reading-001",
                null,
                null
        );

        Set<ConstraintViolation<EdgeReadingRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("Acepta lectura sin time ni id (opcionales)")
    void acceptsReadingWithoutOptionalTimeAndId() {
        EdgeReadingRequest request = new EdgeReadingRequest(
                "esp32-freshsense-1",
                3.0,
                80.0,
                1.0,
                null,
                null,
                "YELLOW",
                "Lácteos"
        );

        Set<ConstraintViolation<EdgeReadingRequest>> violations = validator.validate(request);
        assertTrue(violations.isEmpty());
    }
}
