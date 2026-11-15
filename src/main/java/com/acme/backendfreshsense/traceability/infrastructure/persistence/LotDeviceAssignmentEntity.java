package com.acme.backendfreshsense.traceability.infrastructure.persistence;

import com.acme.backendfreshsense.monitoring.infrastructure.persistence.jpa.DeviceEntity;
import com.acme.backendfreshsense.traceability.domain.LotDeviceAssignment;
import jakarta.persistence.*;
import org.hibernate.annotations.Check;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trace_lot_device_assignments",
        uniqueConstraints = @UniqueConstraint(name = "uk_trace_assignment_active_device", columnNames = "active_device_key"),
        indexes = {
                @Index(name = "ix_trace_assignment_lot_owner_start", columnList = "lot_id, owner_id, started_at"),
                @Index(name = "ix_trace_assignment_device_end", columnList = "device_key, ended_at")
        })
@Check(name = "ck_trace_assignment_interval", constraints = """
        (ended_at IS NULL AND active_device_key IS NOT NULL AND active_device_key = device_key)
        OR (ended_at IS NOT NULL AND ended_at > started_at AND active_device_key IS NULL)
        """)
class LotDeviceAssignmentEntity {
    @Id UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lot_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_trace_assignment_lot"))
    FoodLotEntity lot;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "device_key", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_trace_assignment_device"))
    DeviceEntity device;

    @Column(name = "owner_id", nullable = false, updatable = false)
    long ownerId;

    @Column(name = "active_device_key")
    Long activeDeviceKey;

    @Column(name = "started_at", nullable = false, updatable = false)
    Instant startedAt;

    @Column(name = "ended_at")
    Instant endedAt;

    protected LotDeviceAssignmentEntity() {}

    LotDeviceAssignmentEntity(LotDeviceAssignment assignment, FoodLotEntity lot, DeviceEntity device) {
        id = assignment.id();
        this.lot = lot;
        this.device = device;
        ownerId = assignment.ownerId();
        startedAt = assignment.startedAt();
        activeDeviceKey = assignment.deviceKey();
    }

    LotDeviceAssignment toDomain() {
        return new LotDeviceAssignment(id, lot.getId(), ownerId, device.getId(), device.getDeviceId(), startedAt, endedAt);
    }

    void closeAt(Instant end) {
        endedAt = end;
        activeDeviceKey = null;
    }
}
