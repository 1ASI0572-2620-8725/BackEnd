package com.acme.backendfreshsense.monitoring.infrastructure.persistence.jpa;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SensorObservationRepository extends JpaRepository<SensorObservation, Long> {
    Optional<SensorObservation> findByDevice_IdAndStableKey(long deviceKey, String stableKey);
}
