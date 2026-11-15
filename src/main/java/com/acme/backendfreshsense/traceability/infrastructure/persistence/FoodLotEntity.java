package com.acme.backendfreshsense.traceability.infrastructure.persistence;

import com.acme.backendfreshsense.inventory.infrastructure.persistence.jpa.ProductEntity;
import com.acme.backendfreshsense.traceability.domain.FoodLot;
import jakarta.persistence.*;
import org.hibernate.annotations.Check;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "trace_food_lots",
        uniqueConstraints = @UniqueConstraint(name = "uk_trace_lot_owner_code", columnNames = {"owner_id", "code"}),
        indexes = @Index(name = "ix_trace_lot_owner_created", columnList = "owner_id, created_at"))
@Check(name = "ck_trace_lot_quantity", constraints = "initial_quantity > 0")
class FoodLotEntity {
    @Id UUID id;

    @Column(nullable = false, length = 64, updatable = false)
    String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, updatable = false,
            foreignKey = @ForeignKey(name = "fk_trace_lot_product"))
    ProductEntity product;

    @Column(name = "owner_id", nullable = false, updatable = false)
    long ownerId;

    @Column(name = "initial_quantity", nullable = false, updatable = false, precision = 15, scale = 3)
    BigDecimal initialQuantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12, updatable = false)
    FoodLot.Unit unit;

    @Column(nullable = false, updatable = false)
    Instant receivedAt;

    @Column(updatable = false)
    LocalDate expiresOn;

    @Column(nullable = false, updatable = false)
    Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    FoodLot.Status status;

    @Column(nullable = false, updatable = false)
    UUID initialEvidenceId;

    protected FoodLotEntity() {}

    FoodLotEntity(FoodLot lot, ProductEntity product) {
        id = lot.id();
        code = lot.code();
        this.product = product;
        ownerId = lot.ownerId();
        initialQuantity = lot.initialQuantity();
        unit = lot.unit();
        receivedAt = lot.receivedAt();
        expiresOn = lot.expiresOn();
        createdAt = lot.createdAt();
        status = lot.status();
        initialEvidenceId = lot.initialEvidenceId();
    }

    public UUID getId() {
        return id;
    }

    FoodLot toDomain() {
        return new FoodLot(id, code, product.getId(), ownerId, initialQuantity, unit, receivedAt, expiresOn,
                createdAt, status, initialEvidenceId);
    }
}
