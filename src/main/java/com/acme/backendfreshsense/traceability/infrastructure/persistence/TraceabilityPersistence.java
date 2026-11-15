package com.acme.backendfreshsense.traceability.infrastructure.persistence;

import com.acme.backendfreshsense.inventory.infrastructure.persistence.jpa.ProductEntity;
import com.acme.backendfreshsense.monitoring.infrastructure.persistence.jpa.DeviceEntity;
import com.acme.backendfreshsense.shared.infrastructure.persistence.DatabaseConstraints;
import com.acme.backendfreshsense.traceability.domain.FoodLot;
import com.acme.backendfreshsense.traceability.domain.LotDeviceAssignment;
import com.acme.backendfreshsense.traceability.domain.TraceEvidence;
import com.acme.backendfreshsense.traceability.domain.TraceabilityConflictException;
import com.acme.backendfreshsense.traceability.domain.TraceabilityRepository;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class TraceabilityPersistence implements TraceabilityRepository {
    private final EntityManager entities;
    private final FoodLotJpaRepository lots;
    private final LotDeviceAssignmentJpaRepository assignments;
    private final TraceEvidenceJpaRepository evidences;

    TraceabilityPersistence(EntityManager entities, FoodLotJpaRepository lots,
                            LotDeviceAssignmentJpaRepository assignments, TraceEvidenceJpaRepository evidences) {
        this.entities = entities;
        this.lots = lots;
        this.assignments = assignments;
        this.evidences = evidences;
    }

    @Override
    public void insertLot(FoodLot lot) {
        var product = entities.getReference(ProductEntity.class, lot.productId());
        try {
            entities.persist(new FoodLotEntity(lot, product));
            entities.flush();
        } catch (RuntimeException e) {
            if (DatabaseConstraints.matches(e, "uk_trace_lot_owner_code")) {
                throw new TraceabilityConflictException("El código de lote ya existe", e);
            }
            if (DatabaseConstraints.matches(e, "fk_trace_lot_product")) {
                throw new TraceabilityConflictException("El producto dejó de estar disponible", e);
            }
            throw e;
        }
    }

    @Override
    public void insertEvidence(TraceEvidence evidence) {
        entities.persist(new TraceEvidenceEntity(evidence, entities.getReference(FoodLotEntity.class, evidence.lotId())));
    }

    @Override
    public List<FoodLot> findLots(long ownerId) {
        return lots.findByOwnerIdOrderByCreatedAtDescIdAsc(ownerId).stream().map(FoodLotEntity::toDomain).toList();
    }

    @Override
    public Optional<FoodLot> findLot(UUID lotId, long ownerId) {
        return lots.findByIdAndOwnerId(lotId, ownerId).map(FoodLotEntity::toDomain);
    }

    @Override
    public void insertAssignment(LotDeviceAssignment assignment) {
        var lot = entities.getReference(FoodLotEntity.class, assignment.lotId());
        var device = entities.getReference(DeviceEntity.class, assignment.deviceKey());
        try {
            entities.persist(new LotDeviceAssignmentEntity(assignment, lot, device));
            entities.flush();
        } catch (RuntimeException e) {
            if (DatabaseConstraints.matches(e, "uk_trace_assignment_active_device")) {
                throw new TraceabilityConflictException("El dispositivo ya tiene un lote activo", e);
            }
            throw e;
        }
    }

    @Override
    public boolean isDeviceAssigned(long deviceKey) {
        return assignments.existsByActiveDeviceKey(deviceKey);
    }

    @Override
    public Optional<Instant> lastDeviceEnd(long deviceKey) {
        return assignments.findFirstByDevice_IdAndEndedAtIsNotNullOrderByEndedAtDesc(deviceKey)
                .map(row -> row.endedAt);
    }

    @Override
    public Optional<String> findAssignmentDevice(UUID id, UUID lotId, long ownerId) {
        return assignments.findDeviceId(id, lotId, ownerId);
    }

    @Override
    public Optional<LotDeviceAssignment> findAssignmentForUpdate(UUID id, UUID lotId, long ownerId) {
        return assignments.findForUpdate(id, lotId, ownerId).map(LotDeviceAssignmentEntity::toDomain);
    }

    @Override
    public void closeAssignment(LotDeviceAssignment assignment) {
        assignments.getReferenceById(assignment.id()).closeAt(assignment.endedAt());
    }

    @Override
    public List<LotDeviceAssignment> findAssignments(UUID lotId, long ownerId) {
        return assignments.findByLot_IdAndOwnerIdOrderByStartedAtAscIdAsc(lotId, ownerId)
                .stream().map(LotDeviceAssignmentEntity::toDomain).toList();
    }

    @Override
    public List<TraceEvidence> findEvidences(UUID lotId, long ownerId) {
        return evidences.findByLot_IdAndLot_OwnerIdOrderByCreatedAtAscIdAsc(lotId, ownerId)
                .stream().map(TraceEvidenceEntity::toDomain).toList();
    }

    @Override
    public Optional<TraceEvidence> findEvidence(UUID id, long ownerId) {
        return evidences.findByIdAndLot_OwnerId(id, ownerId).map(TraceEvidenceEntity::toDomain);
    }
}
