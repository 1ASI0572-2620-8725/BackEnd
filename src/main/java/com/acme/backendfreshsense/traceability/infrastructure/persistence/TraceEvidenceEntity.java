package com.acme.backendfreshsense.traceability.infrastructure.persistence;

import com.acme.backendfreshsense.traceability.domain.TraceEvidence;
import jakarta.persistence.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Immutable
@Table(name = "trace_evidences",
        uniqueConstraints = @UniqueConstraint(name = "uk_trace_evidence_event", columnNames = "business_event_id"),
        indexes = @Index(name = "ix_trace_evidence_lot_created", columnList = "lot_id, created_at"))
class TraceEvidenceEntity {
    @Id UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lot_id", nullable = false, foreignKey = @ForeignKey(name = "fk_trace_evidence_lot"))
    FoodLotEntity lot;

    @Column(name = "business_event_id", nullable = false)
    UUID businessEventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    TraceEvidence.Type type;

    @Column(nullable = false)
    int schemaVersion;

    @Column(nullable = false, columnDefinition = "TEXT")
    String canonicalSnapshot;

    @Column(nullable = false, length = 64)
    String sha256;

    @Column(nullable = false)
    Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    TraceEvidence.Source source;

    protected TraceEvidenceEntity() {}

    TraceEvidenceEntity(TraceEvidence evidence, FoodLotEntity lot) {
        id = evidence.id();
        this.lot = lot;
        businessEventId = evidence.businessEventId();
        type = evidence.type();
        schemaVersion = evidence.schemaVersion();
        canonicalSnapshot = evidence.canonicalSnapshot();
        sha256 = evidence.sha256();
        createdAt = evidence.createdAt();
        source = evidence.source();
    }

    TraceEvidence toDomain() {
        return new TraceEvidence(id, lot.getId(), businessEventId, type, schemaVersion, canonicalSnapshot,
                sha256, createdAt, source);
    }
}
