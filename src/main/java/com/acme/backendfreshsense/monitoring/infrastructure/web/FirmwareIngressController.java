package com.acme.backendfreshsense.monitoring.infrastructure.web;

import com.acme.backendfreshsense.monitoring.application.service.SensorIngestion;
import com.acme.backendfreshsense.monitoring.infrastructure.persistence.jpa.DeviceJpaRepository;
import com.acme.backendfreshsense.monitoring.infrastructure.persistence.jpa.SensorObservation.Source;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Locale;

@RestController
@EnableConfigurationProperties(FirmwareIngressSettings.class)
class FirmwareIngressController {
    record FirmwareReading(
            @NotBlank @Size(max = 100) String deviceId,
            @Size(max = 100) String timestamp,
            @NotNull @Digits(integer = 3, fraction = 6) @DecimalMin("-40") @DecimalMax("80") BigDecimal temperatureC,
            @NotNull @Digits(integer = 3, fraction = 6) @DecimalMin("0") @DecimalMax("100") BigDecimal humidityPct,
            @NotNull @Digits(integer = 5, fraction = 6) @DecimalMin("0") @DecimalMax("10000") BigDecimal ethylenePpm,
            @Size(max = 1024) String meta) {}

    private final FirmwareIngressSettings settings;
    private final DeviceJpaRepository devices;
    private final SensorIngestion ingress;

    FirmwareIngressController(FirmwareIngressSettings settings, DeviceJpaRepository devices, SensorIngestion ingress) {
        this.settings = settings;
        this.devices = devices;
        this.ingress = ingress;
    }

    @PostMapping("/api/v1/sensor-readings")
    ResponseEntity<SensorIngestion.Result> ingest(
            @RequestHeader(value = "X-Device-Token", required = false) String token,
            @RequestHeader(value = "Idempotency-Key", required = false) String readingId,
            @RequestHeader(value = "X-Measurement-Clock", required = false) String clock,
            @Valid @RequestBody FirmwareReading request) {
        var binding = settings.authenticate(request.deviceId(), token).orElseThrow(FirmwareIngressController::unauthorized);
        var device = devices.findById(binding.deviceKey()).filter(row -> request.deviceId().equals(row.getDeviceId()))
                .orElseThrow(FirmwareIngressController::unauthorized);
        if (readingId != null && !readingId.matches("[!-~]{1,128}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Idempotency-Key debe contener 1 a 128 caracteres ASCII visibles");
        }
        if (clock != null && !"SYNCED".equals(clock) && !"UNSYNCED".equals(clock)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Estado de reloj inválido");
        }
        Source source = binding.source();
        if (source != Source.SIMULATED && reportsSimulation(request.meta())) source = Source.UNVERIFIED_SIMULATION;
        var sample = new SensorIngestion.Sample(readingId, request.timestamp(), request.temperatureC().doubleValue(),
                request.humidityPct().doubleValue(), request.ethylenePpm().doubleValue(), null, null, request.meta(),
                binding.zone() == null ? null : binding.zone().getId(), clock, source, binding.profile());
        var result = ingress.ingest(device.getUserId(), device.getId(), device.getDeviceId(), sample);
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(result);
    }

    private static boolean reportsSimulation(String metadata) {
        if (metadata == null) return false;
        for (String field : metadata.toLowerCase(Locale.ROOT).split(";")) {
            if (switch (field.trim()) {
                case "origen=supuesto", "supuesto=1", "simulacion=1" -> true;
                default -> false;
            }) return true;
        }
        return false;
    }

    private static ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Dispositivo no autorizado");
    }
}
