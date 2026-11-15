package com.acme.backendfreshsense.traceability.application;

import com.acme.backendfreshsense.shared.infrastructure.exceptions.ResourceNotFoundException;
import com.acme.backendfreshsense.monitoring.infrastructure.persistence.jpa.SensorObservation;
import com.acme.backendfreshsense.monitoring.infrastructure.persistence.jpa.SensorObservationRepository;
import com.acme.backendfreshsense.traceability.domain.FoodLot;
import com.acme.backendfreshsense.traceability.domain.LotDeviceAssignment;
import com.acme.backendfreshsense.traceability.domain.TraceEvidence;
import com.acme.backendfreshsense.traceability.domain.TraceabilityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.acme.backendfreshsense.traceability.application.EvidenceCodec.LotRegisteredSnapshot;
import static com.acme.backendfreshsense.traceability.application.EvidenceCodec.CriticalTemperatureSnapshot;
import static com.acme.backendfreshsense.traceability.application.EvidenceCodec.SCHEMA_VERSION;

@Service
@Transactional(readOnly = true)
public class TraceabilityService {
    private static final int TEMPERATURE_SCHEMA_V1 = 1;
    private static final DateTimeFormatter UTC_MICROS = new DateTimeFormatterBuilder().appendInstant(6).toFormatter();

    public enum TemperatureOutcome {
        NO_STABLE_ID, UNTRUSTED_TIME, NO_ASSIGNMENT, PROFILE_NOT_APPLICABLE, NON_CRITICAL, EVIDENCE_CREATED, REPLAY
    }

    public record TemperatureResult(TemperatureOutcome outcome, UUID evidenceId) {}

    private final TraceabilityRepository records;
    private final TraceabilityResources resources;
    private final EvidenceCodec codec;
    private final SensorObservationRepository observations;

    public TraceabilityService(TraceabilityRepository records, TraceabilityResources resources, EvidenceCodec codec,
                               SensorObservationRepository observations) {
        this.records = records;
        this.resources = resources;
        this.codec = codec;
        this.observations = observations;
    }

    @Transactional
    public FoodLot register(long ownerId, long productId, String code, BigDecimal quantity,
                            FoodLot.Unit unit, Instant receivedAt, LocalDate expiresOn) {
        var product = resources.findOwnedProduct(productId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Producto no encontrado"));
        var lot = new FoodLot(UUID.randomUUID(), code, productId, ownerId, quantity, unit,
                receivedAt.truncatedTo(ChronoUnit.MICROS), expiresOn, now(), FoodLot.Status.REGISTERED,
                UUID.randomUUID());
        UUID eventId = UUID.randomUUID();
        String snapshot = codec.encode(LotRegisteredSnapshot.from(lot, eventId, product.name(), product.category()));
        var evidence = new TraceEvidence(lot.initialEvidenceId(), lot.id(), eventId, TraceEvidence.Type.LOT_REGISTERED,
                SCHEMA_VERSION, snapshot, codec.sha256(snapshot), lot.createdAt(), TraceEvidence.Source.AUTHENTICATED_USER);
        records.insertLot(lot);
        records.insertEvidence(evidence);
        return lot;
    }

    public FoodLot lot(long ownerId, UUID lotId) {
        return records.findLot(lotId, ownerId).orElseThrow(() -> new ResourceNotFoundException("Lote no encontrado"));
    }

    public List<FoodLot> lots(long ownerId) {
        return records.findLots(ownerId);
    }

    @Transactional
    public LotDeviceAssignment assign(long ownerId, UUID lotId, String deviceId) {
        // El bloqueo de dispositivo precede a la primera lectura de instantánea de InnoDB.
        var device = resources.lockOwnedDevice(deviceId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Dispositivo no encontrado"));
        lot(ownerId, lotId);
        if (records.isDeviceAssigned(device.key())) {
            throw new IllegalStateException("El dispositivo ya tiene un lote activo");
        }
        Instant start = now();
        Instant previousEnd = records.lastDeviceEnd(device.key()).orElse(start);
        var assignment = new LotDeviceAssignment(UUID.randomUUID(), lotId, ownerId, device.key(), device.deviceId(),
                previousEnd.isAfter(start) ? previousEnd : start, null);
        records.insertAssignment(assignment);
        return assignment;
    }

    @Transactional
    public LotDeviceAssignment close(long ownerId, UUID lotId, UUID assignmentId) {
        lot(ownerId, lotId);
        String deviceId = records.findAssignmentDevice(assignmentId, lotId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Asociación no encontrada"));
        resources.lockOwnedDevice(deviceId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Dispositivo no encontrado"));
        var assignment = records.findAssignmentForUpdate(assignmentId, lotId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Asociación no encontrada")).close(now());
        records.closeAssignment(assignment);
        return assignment;
    }

    public List<LotDeviceAssignment> assignments(long ownerId, UUID lotId) {
        lot(ownerId, lotId);
        return records.findAssignments(lotId, ownerId);
    }

    /** Consulta por la identidad autenticada y el instante medido persistido. */
    public Optional<LotDeviceAssignment> assignmentAt(long ownerId, long deviceKey, Instant measuredAt) {
        return records.findAssignmentAt(ownerId, deviceKey, measuredAt.truncatedTo(ChronoUnit.MICROS));
    }

    public List<TraceEvidence> evidences(long ownerId, UUID lotId) {
        lot(ownerId, lotId);
        return records.findEvidences(lotId, ownerId);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public TemperatureResult observeTemperature(long readingId) {
        var observation = observations.findById(readingId).orElseThrow();
        if (observation.getStableKey() == null) return outcome(TemperatureOutcome.NO_STABLE_ID);
        if (!trusted(observation)) return outcome(TemperatureOutcome.UNTRUSTED_TIME);
        long owner = observation.getReading().getUserId();
        var assignment = assignmentAt(owner, observation.getDevice().getId(), observation.getMeasuredAt());
        if (assignment.isEmpty()) return outcome(TemperatureOutcome.NO_ASSIGNMENT);
        if (!profileApplies(observation, assignment.get(), owner)) return outcome(TemperatureOutcome.PROFILE_NOT_APPLICABLE);
        var breach = TemperatureRule.check(observation.temperature());
        if (breach.isEmpty()) return outcome(TemperatureOutcome.NON_CRITICAL);

        UUID id = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        var source = temperatureSource(observation);
        var snapshot = snapshot(observation, assignment.get(), breach.get(), id, event, source);
        String canonical = codec.encode(snapshot);
        records.insertEvidence(new TraceEvidence(id, assignment.get().lotId(), event, TraceEvidence.Type.CRITICAL_TEMPERATURE,
                TEMPERATURE_SCHEMA_V1, canonical, codec.sha256(canonical), observation.getReceivedAt(), source, readingId));
        return new TemperatureResult(TemperatureOutcome.EVIDENCE_CREATED, id);
    }

    public TemperatureResult replayedTemperature(long readingId) {
        return new TemperatureResult(TemperatureOutcome.REPLAY,
                records.findEvidenceForReading(readingId).map(TraceEvidence::id).orElse(null));
    }

    public TraceEvidence evidence(long ownerId, UUID evidenceId) {
        return records.findEvidence(evidenceId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Evidencia no encontrada"));
    }

    public boolean verify(long ownerId, UUID evidenceId) {
        var evidence = evidence(ownerId, evidenceId);
        if (evidence.type() == TraceEvidence.Type.CRITICAL_TEMPERATURE) return verifyTemperature(ownerId, evidence);
        var lot = lot(ownerId, evidence.lotId());
        if (evidence.schemaVersion() != SCHEMA_VERSION || !evidence.id().equals(lot.initialEvidenceId())
                || !evidence.createdAt().equals(lot.createdAt())) {
            return false;
        }
        LotRegisteredSnapshot stored;
        try {
            stored = codec.decode(evidence.canonicalSnapshot());
        } catch (IllegalArgumentException e) {
            return false;
        }
        // Descripción histórica del producto: procede del sobre, no del inventario mutable.
        var expected = LotRegisteredSnapshot.from(lot, evidence.businessEventId(), stored.productName(), stored.productCategory());
        return expected.equals(stored)
                && evidence.type().name().equals(stored.eventType())
                && evidence.source().name().equals(stored.source())
                && evidence.sha256().equals(codec.sha256(codec.encode(stored)));
    }

    private boolean verifyTemperature(long owner, TraceEvidence evidence) {
        if (evidence.readingId() == null || evidence.schemaVersion() != TEMPERATURE_SCHEMA_V1) return false;
        var observation = observations.findById(evidence.readingId()).orElse(null);
        if (observation == null || !observation.getReading().getUserId().equals(owner)
                || observation.getStableKey() == null || !trusted(observation)
                || !evidence.createdAt().equals(observation.getReceivedAt())
                || evidence.source() != temperatureSource(observation)) {
            return false;
        }
        var assignment = assignmentAt(owner, observation.getDevice().getId(), observation.getMeasuredAt()).orElse(null);
        if (assignment == null || !evidence.lotId().equals(assignment.lotId()) || !profileApplies(observation, assignment, owner)) {
            return false;
        }
        var breach = TemperatureRule.check(observation.temperature()).orElse(null);
        if (breach == null) return false;
        String expected = codec.encode(snapshot(observation, assignment, breach, evidence.id(),
                evidence.businessEventId(), evidence.source()));
        return evidence.canonicalSnapshot().equals(expected) && evidence.sha256().equals(codec.sha256(expected));
    }

    private boolean profileApplies(SensorObservation observation, LotDeviceAssignment assignment, long owner) {
        var initial = records.findLot(assignment.lotId(), owner)
                .flatMap(lot -> records.findEvidence(lot.initialEvidenceId(), owner))
                .orElse(null);
        if (initial == null || initial.type() != TraceEvidence.Type.LOT_REGISTERED
                || !initial.sha256().equals(codec.sha256(initial.canonicalSnapshot()))) {
            return false;
        }
        try {
            return TemperatureRule.applies(observation.getRuleProfile(), codec.decode(initial.canonicalSnapshot()).productCategory());
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean trusted(SensorObservation observation) {
        return observation.getTimeQuality() == SensorObservation.TimeQuality.TRUSTED
                && (observation.getSource() == SensorObservation.Source.AUTHENTICATED_GATEWAY
                    || observation.getSource() == SensorObservation.Source.SIMULATED);
    }

    private static TraceEvidence.Source temperatureSource(SensorObservation observation) {
        return observation.getSource() == SensorObservation.Source.SIMULATED
                ? TraceEvidence.Source.SIMULATED : TraceEvidence.Source.AUTHENTICATED_GATEWAY;
    }

    private static TemperatureResult outcome(TemperatureOutcome outcome) {
        return new TemperatureResult(outcome, null);
    }

    private static CriticalTemperatureSnapshot snapshot(SensorObservation observation, LotDeviceAssignment assignment,
                                                        TemperatureRule.Breach breach, UUID id, UUID event,
                                                        TraceEvidence.Source source) {
        return new CriticalTemperatureSnapshot("freshsense.trace.critical-temperature", TEMPERATURE_SCHEMA_V1,
                id.toString(), event.toString(), TraceEvidence.Type.CRITICAL_TEMPERATURE.name(), observation.getId().toString(),
                observation.getReading().getDeviceId(), assignment.lotId().toString(), assignment.id().toString(),
                observation.temperature().toPlainString(), "CELSIUS", TemperatureRule.PROFILE, TemperatureRule.VERSION,
                breach.direction(), breach.threshold().toPlainString(), UTC_MICROS.format(observation.getMeasuredAt()),
                UTC_MICROS.format(observation.getReceivedAt()), source.name());
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
