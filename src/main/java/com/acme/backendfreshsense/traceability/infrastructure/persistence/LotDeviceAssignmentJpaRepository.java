package com.acme.backendfreshsense.traceability.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface LotDeviceAssignmentJpaRepository extends JpaRepository<LotDeviceAssignmentEntity, UUID> {
    boolean existsByActiveDeviceKey(long deviceKey);

    Optional<LotDeviceAssignmentEntity> findFirstByDevice_IdAndEndedAtIsNotNullOrderByEndedAtDesc(long deviceKey);

    @Query("""
            select a.device.deviceId from LotDeviceAssignmentEntity a
            where a.id = :id and a.lot.id = :lotId and a.ownerId = :ownerId
            """)
    Optional<String> findDeviceId(@Param("id") UUID id, @Param("lotId") UUID lotId, @Param("ownerId") long ownerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select a from LotDeviceAssignmentEntity a
            where a.id = :id and a.lot.id = :lotId and a.ownerId = :ownerId
            """)
    Optional<LotDeviceAssignmentEntity> findForUpdate(@Param("id") UUID id, @Param("lotId") UUID lotId,
                                                     @Param("ownerId") long ownerId);

    List<LotDeviceAssignmentEntity> findByLot_IdAndOwnerIdOrderByStartedAtAscIdAsc(UUID lotId, long ownerId);

    @Query("""
            select a from LotDeviceAssignmentEntity a
            where a.ownerId = :ownerId and a.lot.ownerId = :ownerId and a.device.id = :deviceKey
              and a.startedAt <= :measuredAt and (a.endedAt is null or a.endedAt > :measuredAt)
            """)
    Optional<LotDeviceAssignmentEntity> findAt(long ownerId, long deviceKey, Instant measuredAt);
}
