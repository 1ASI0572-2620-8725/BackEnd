package com.acme.backendfreshsense.monitoring.application.service;

import com.acme.backendfreshsense.monitoring.application.dto.MonitoringReadingDto;
import com.acme.backendfreshsense.monitoring.infrastructure.persistence.jpa.DeviceJpaRepository;
import com.acme.backendfreshsense.monitoring.infrastructure.persistence.jpa.MonitoringJpaRepository;
import com.acme.backendfreshsense.monitoring.infrastructure.persistence.jpa.SensorObservation;
import com.acme.backendfreshsense.monitoring.infrastructure.persistence.jpa.SensorObservation.Source;
import com.acme.backendfreshsense.monitoring.infrastructure.persistence.jpa.SensorObservation.TimeQuality;
import com.acme.backendfreshsense.monitoring.infrastructure.persistence.jpa.SensorObservationRepository;
import com.acme.backendfreshsense.traceability.application.EvidenceCodec;
import com.acme.backendfreshsense.traceability.application.TraceabilityService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

@Service
public class SensorIngestion {
    public record Sample(String externalId, String timestamp, Double temperature, Double humidity, Double ethylene,
                         String status, String category, String metadata, String zone, String clock,
                         Source source, String profile) {}

    private record Payload(String timestamp, Double temperature, Double humidity, Double ethylene,
                           String status, String category, String metadata, String clock) {}

    public record Result(MonitoringReadingDto reading, Instant measuredAt, Instant receivedAt, TimeQuality timeQuality,
                         Source source, boolean duplicate, TraceabilityService.TemperatureResult traceability) {}

    private final MonitoringService monitoring;
    private final MonitoringJpaRepository readings;
    private final DeviceJpaRepository devices;
    private final SensorObservationRepository observations;
    private final TraceabilityService traceability;
    private final EvidenceCodec codec;
    private final ObjectMapper json;
    private final EntityManager entities;

    public SensorIngestion(MonitoringService monitoring, MonitoringJpaRepository readings, DeviceJpaRepository devices,
                           SensorObservationRepository observations, TraceabilityService traceability, EvidenceCodec codec,
                           ObjectMapper json, EntityManager entities) {
        this.monitoring = monitoring;
        this.readings = readings;
        this.devices = devices;
        this.observations = observations;
        this.traceability = traceability;
        this.codec = codec;
        this.json = json;
        this.entities = entities;
    }

    @Transactional
    public Result ingest(long owner, long deviceKey, String deviceId, Sample sample) {
        var received = Instant.now().truncatedTo(ChronoUnit.MICROS);
        // Matches the assignment lock and serializes retransmissions before the first InnoDB snapshot read.
        var device = devices.findByDeviceIdAndUserId(deviceId, owner)
                .filter(row -> row.getId() == deviceKey && row.getDeviceId().equals(deviceId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Dispositivo no autorizado"));
        String stableKey = sample.externalId() == null || sample.externalId().isBlank() ? null
                : codec.sha256(sample.externalId());
        String payload = fingerprint(sample);
        if (stableKey != null) {
            var previous = observations.findByDevice_IdAndStableKey(deviceKey, stableKey);
            if (previous.isPresent()) {
                var row = previous.get();
                if (!payload.equals(row.getPayloadDigest())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Identificador reutilizado con datos diferentes");
                }
                return result(monitoring.findReading(row.getId()).orElseThrow(), row, true,
                        traceability.replayedTemperature(row.getId()));
            }
        }

        boolean legacy = sample.source() == Source.LEGACY_DEVICE;
        ZoneId zone = sample.zone() == null ? null : ZoneId.of(sample.zone());
        boolean synchronizedClock = "SYNCED".equals(sample.clock())
                && (sample.source() == Source.AUTHENTICATED_GATEWAY || sample.source() == Source.SIMULATED);
        var time = legacy ? MeasurementTime.legacy(sample.timestamp())
                : MeasurementTime.interpret(sample.timestamp(), zone, synchronizedClock, received);
        LocalDateTime display = legacy ? MeasurementTime.legacyDisplayTime(sample.timestamp())
                : LocalDateTime.ofInstant(time.quality() == TimeQuality.TRUSTED ? time.measuredAt() : received, ZoneOffset.UTC);
        var reading = monitoring.saveFromEdge(owner, deviceId, sample.externalId(), sample.temperature(), sample.humidity(),
                sample.ethylene(), display, sample.status(), sample.category());
        var observation = new SensorObservation(readings.getReferenceById(reading.id()), device, stableKey, payload,
                sample.timestamp(), sample.metadata(), time.measuredAt(), received, time.quality(), sample.source(), sample.profile());
        entities.persist(observation);
        entities.flush();
        var outcome = traceability.observeTemperature(observation.getId());
        return result(reading, observation, false, outcome);
    }

    private String fingerprint(Sample sample) {
        try {
            return codec.sha256(json.writeValueAsString(new Payload(sample.timestamp(), sample.temperature(),
                    sample.humidity(), sample.ethylene(), sample.status(), sample.category(), sample.metadata(), sample.clock())));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo normalizar la lectura", e);
        }
    }

    private static Result result(MonitoringReadingDto reading, SensorObservation metadata, boolean duplicate,
                                 TraceabilityService.TemperatureResult outcome) {
        return new Result(reading, metadata.getMeasuredAt(), metadata.getReceivedAt(), metadata.getTimeQuality(),
                metadata.getSource(), duplicate, outcome);
    }
}
