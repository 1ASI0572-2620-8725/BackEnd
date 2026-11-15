package com.acme.backendfreshsense.monitoring.infrastructure.persistence.jpa;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.List;
import java.util.Optional;

public interface DeviceJpaRepository extends JpaRepository<DeviceEntity, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<DeviceEntity> findByDeviceIdAndUserId(String deviceId, Long userId);

    List<DeviceEntity> findByUserId(Long userId);
    Optional<DeviceEntity> findByDeviceId(String deviceId);
    Optional<DeviceEntity> findByPairingCode(String pairingCode);
    boolean existsByDeviceId(String deviceId);
}
