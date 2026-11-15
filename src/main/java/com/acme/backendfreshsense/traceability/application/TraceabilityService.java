package com.acme.backendfreshsense.traceability.application;

import com.acme.backendfreshsense.shared.infrastructure.exceptions.ResourceNotFoundException;
import com.acme.backendfreshsense.traceability.domain.FoodLot;
import com.acme.backendfreshsense.traceability.domain.LotDeviceAssignment;
import com.acme.backendfreshsense.traceability.domain.TraceEvidence;
import com.acme.backendfreshsense.traceability.domain.TraceabilityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.acme.backendfreshsense.traceability.application.EvidenceCodec.LotRegisteredSnapshot;
import static com.acme.backendfreshsense.traceability.application.EvidenceCodec.SCHEMA_VERSION;

@Service
@Transactional(readOnly = true)
public class TraceabilityService {
    private final TraceabilityRepository records;
    private final TraceabilityResources resources;
    private final EvidenceCodec codec;

    public TraceabilityService(TraceabilityRepository records, TraceabilityResources resources, EvidenceCodec codec) {
        this.records = records;
        this.resources = resources;
        this.codec = codec;
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

    /** El futuro consumidor de Monitoring debe usar la identidad autenticada y el instante medido persistido. */
    public Optional<LotDeviceAssignment> assignmentAt(long ownerId, long deviceKey, Instant measuredAt) {
        return records.findAssignmentAt(ownerId, deviceKey, measuredAt.truncatedTo(ChronoUnit.MICROS));
    }

    public List<TraceEvidence> evidences(long ownerId, UUID lotId) {
        lot(ownerId, lotId);
        return records.findEvidences(lotId, ownerId);
    }

    public TraceEvidence evidence(long ownerId, UUID evidenceId) {
        return records.findEvidence(evidenceId, ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Evidencia no encontrada"));
    }

    public boolean verify(long ownerId, UUID evidenceId) {
        var evidence = evidence(ownerId, evidenceId);
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

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
