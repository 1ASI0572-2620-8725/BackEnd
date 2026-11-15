package com.acme.backendfreshsense.traceability.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface FoodLotJpaRepository extends JpaRepository<FoodLotEntity, UUID> {
    Optional<FoodLotEntity> findByIdAndOwnerId(UUID id, long ownerId);
    List<FoodLotEntity> findByOwnerIdOrderByCreatedAtDescIdAsc(long ownerId);
}
