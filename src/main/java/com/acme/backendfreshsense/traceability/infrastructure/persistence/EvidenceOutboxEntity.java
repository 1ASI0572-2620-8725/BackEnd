package com.acme.backendfreshsense.traceability.infrastructure.persistence;

import com.acme.backendfreshsense.traceability.infrastructure.blockchain.BlockchainSettings.Target;
import com.acme.backendfreshsense.traceability.infrastructure.blockchain.AnchoringStatus.Publication;
import com.acme.backendfreshsense.traceability.infrastructure.blockchain.AnchoringStatus.Verification;
import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "trace_evidence_outbox",
        uniqueConstraints = @UniqueConstraint(name = "uk_trace_outbox_active_slot", columnNames = "active_slot"),
        indexes = @Index(name = "ix_trace_outbox_due", columnList = "status,next_attempt_at,evidence_id"))
class EvidenceOutboxEntity {
    @Id
    @Column(name = "evidence_id")
    UUID id;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "evidence_id", foreignKey = @ForeignKey(name = "fk_trace_outbox_evidence"))
    TraceEvidenceEntity evidence;

    @Version long version;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    Publication status = Publication.PENDING;
    @Column(nullable = false)
    int attempts;
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    Verification lastError;
    @Column(name = "next_attempt_at")
    Instant nextAttemptAt;
    @Column(length = 140)
    String targetKey;
    Long chainId;
    @Column(length = 42)
    String contractAddress;
    @Column(length = 66)
    String deploymentTransaction;
    @Column(name = "active_slot", length = 140)
    String activeSlot;
    @Column(length = 2048)
    String signedTransaction;
    @Column(length = 24)
    String nonce;
    @Column(length = 66)
    String transactionHash;
    Long blockNumber;
    @Column(length = 66)
    String blockHash;
    Instant confirmedAt;

    protected EvidenceOutboxEntity() {}

    EvidenceOutboxEntity(TraceEvidenceEntity evidence, Target target, Instant createdAt) {
        this.evidence = evidence;
        nextAttemptAt = createdAt;
        if (target != null) bind(target);
    }

    void bind(Target target) {
        targetKey = target.key();
        chainId = target.chainId();
        contractAddress = target.contract();
        deploymentTransaction = target.deploymentTransaction();
    }

    Target target() {
        return chainId == null ? null : new Target(chainId, contractAddress, deploymentTransaction);
    }
}
