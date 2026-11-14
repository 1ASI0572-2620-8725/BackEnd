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
 * Pruebas unitarias del contrato de claim Edge (TS41).
 */
class ClaimRequestValidationTest {

    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    @DisplayName("Acepta un código de emparejamiento no vacío")
    void acceptsValidPairingCode() {
        Set<ConstraintViolation<ClaimRequest>> violations = validator.validate(new ClaimRequest("7K4Q2P"));
        assertTrue(violations.isEmpty());
    }

    @Test
    @DisplayName("Rechaza código vacío")
    void rejectsBlankCode() {
        Set<ConstraintViolation<ClaimRequest>> violations = validator.validate(new ClaimRequest("  "));
        assertFalse(violations.isEmpty());
    }

    @Test
    @DisplayName("Rechaza código nulo")
    void rejectsNullCode() {
        Set<ConstraintViolation<ClaimRequest>> violations = validator.validate(new ClaimRequest(null));
        assertFalse(violations.isEmpty());
    }
}
