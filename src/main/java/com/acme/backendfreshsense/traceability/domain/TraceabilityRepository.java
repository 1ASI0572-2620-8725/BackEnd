package com.acme.backendfreshsense.traceability.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TraceabilityRepository {
    void insertLot(FoodLot lot);
    void insertEvidence(TraceEvidence evidence);
    List<FoodLot> findLots(long ownerId);
    Optional<FoodLot> findLot(UUID lotId, long ownerId);

    void insertAssignment(LotDeviceAssignment assignment);
    boolean isDeviceAssigned(long deviceKey);
    Optional<Instant> lastDeviceEnd(long deviceKey);
    Optional<String> findAssignmentDevice(UUID id, UUID lotId, long ownerId);
    Optional<LotDeviceAssignment> findAssignmentForUpdate(UUID id, UUID lotId, long ownerId);
    void closeAssignment(LotDeviceAssignment assignment);
    List<LotDeviceAssignment> findAssignments(UUID lotId, long ownerId);

    List<TraceEvidence> findEvidences(UUID lotId, long ownerId);
    Optional<TraceEvidence> findEvidence(UUID id, long ownerId);
}
