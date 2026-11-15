package com.acme.backendfreshsense.traceability.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface TraceEvidenceJpaRepository extends JpaRepository<TraceEvidenceEntity, UUID> {
    Optional<TraceEvidenceEntity> findByIdAndLot_OwnerId(UUID id, long ownerId);
    List<TraceEvidenceEntity> findByLot_IdAndLot_OwnerIdOrderByCreatedAtAscIdAsc(UUID lotId, long ownerId);
}
