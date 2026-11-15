package com.acme.backendfreshsense.monitoring.infrastructure.persistence.jpa;

import jakarta.persistence.*;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Getter
@Immutable
@Table(name = "monitoring_sensor_observations", uniqueConstraints = @UniqueConstraint(
        name = "uk_sensor_device_reading", columnNames = {"device_key", "stable_key"}))
public class SensorObservation {
    public enum Source { LEGACY_DEVICE, DEVICE_UNVERIFIED, AUTHENTICATED_GATEWAY, SIMULATED, UNVERIFIED_SIMULATION }
    public enum TimeQuality { TRUSTED, MISSING, INVALID, NO_ZONE, FUTURE, STALE, UNSYNCHRONIZED }

    @Id
    @Column(name = "reading_id")
    private Long id;
    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "reading_id", foreignKey = @ForeignKey(name = "fk_sensor_monitoring_reading"))
    private MonitoringReadingEntity reading;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_key", nullable = false, foreignKey = @ForeignKey(name = "fk_sensor_device"))
    private DeviceEntity device;
    @Column(name = "stable_key", length = 64)
    private String stableKey;
    @Column(nullable = false, length = 64)
    private String payloadDigest;
    @Column(length = 100)
    private String originalTimestamp;
    @Column(length = 1024)
    private String metadata;
    private Instant measuredAt;
    @Column(nullable = false)
    private Instant receivedAt;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24, columnDefinition = "varchar(24)")
    private TimeQuality timeQuality;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32, columnDefinition = "varchar(32)")
    private Source source;
    @Column(length = 24)
    private String ruleProfile;

    protected SensorObservation() {}

    public SensorObservation(MonitoringReadingEntity reading, DeviceEntity device, String stableKey,
                             String payloadDigest, String originalTimestamp, String metadata, Instant measuredAt,
                             Instant receivedAt, TimeQuality timeQuality, Source source, String ruleProfile) {
        this.reading = reading;
        this.device = device;
        this.stableKey = stableKey;
        this.payloadDigest = payloadDigest;
        this.originalTimestamp = originalTimestamp;
        this.metadata = metadata;
        this.measuredAt = measuredAt;
        this.receivedAt = receivedAt;
        this.timeQuality = timeQuality;
        this.source = source;
        this.ruleProfile = ruleProfile;
    }

    public BigDecimal temperature() {
        return BigDecimal.valueOf(reading.getTemperature()).stripTrailingZeros();
    }
}
