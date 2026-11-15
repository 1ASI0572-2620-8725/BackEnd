package com.acme.backendfreshsense.traceability.infrastructure.persistence;

import com.acme.backendfreshsense.traceability.domain.TraceEvidence;
import com.acme.backendfreshsense.traceability.infrastructure.blockchain.AnchoringStatus.Publication;
import com.acme.backendfreshsense.traceability.infrastructure.blockchain.AnchoringStatus.Verification;
import com.acme.backendfreshsense.traceability.infrastructure.blockchain.BlockchainSettings.Target;
import jakarta.persistence.EntityManager;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
@Transactional(readOnly = true)
public class EvidenceOutbox {
    public record Signed(String hash, BigInteger nonce, String raw) {
        @Override public String toString() { return "Signed[hash=" + hash + "]"; }
    }

    public record Confirmation(String hash, long block, String blockHash) {}

    public record Job(TraceEvidence evidence, long version, Publication status, int attempts, Verification lastError,
                      Instant nextAttemptAt, Target target, Signed signed, String transactionHash,
                      Long blockNumber, String blockHash, Instant confirmedAt) {
        public UUID id() { return evidence.id(); }
        @Override public String toString() { return "Job[id=" + id() + ", status=" + status + "]"; }
    }

    private final EvidenceOutboxJpaRepository rows;
    private final EntityManager entities;

    EvidenceOutbox(EvidenceOutboxJpaRepository rows, EntityManager entities) {
        this.rows = rows;
        this.entities = entities;
    }

    public Optional<Job> find(UUID id) {
        return rows.findById(id).map(EvidenceOutbox::snapshot);
    }

    public Optional<Job> next(Target target, String writer, Instant now) {
        var active = rows.findByActiveSlot(writerSlot(target, writer));
        if (active.isPresent()) {
            // Preserve the writer nonce until the signed transaction has been reconciled.
            return active.filter(row -> target.equals(row.target()) && !row.nextAttemptAt.isAfter(now))
                    .map(EvidenceOutbox::snapshot);
        }
        return rows.due(target.key(), List.of(Publication.PENDING, Publication.RETRYABLE_FAILURE),
                now, PageRequest.of(0, 1)).stream().findFirst().map(EvidenceOutbox::snapshot);
    }

    @Transactional
    public Job attempt(Job job, Target target, Instant recoveryAt) {
        var row = current(job);
        if (row.targetKey == null) row.bind(target);
        if (row.attempts < Integer.MAX_VALUE) row.attempts++;
        row.nextAttemptAt = recoveryAt;
        return save(row);
    }

    @Transactional
    public Job prepare(Job job, Signed signed, String writer) {
        var row = current(job);
        row.activeSlot = writerSlot(job.target(), writer);
        row.signedTransaction = signed.raw();
        row.nonce = signed.nonce().toString();
        row.transactionHash = signed.hash();
        row.status = Publication.PREPARED;
        row.lastError = null;
        return save(row);
    }

    @Transactional
    public void submitted(Job job) {
        var row = current(job);
        row.status = Publication.SUBMITTED;
        row.lastError = null;
    }

    @Transactional
    public void retry(Job job, Verification error) {
        var row = current(job);
        if (row.signedTransaction == null) row.status = Publication.RETRYABLE_FAILURE;
        row.lastError = error;
    }

    @Transactional
    public void fail(Job job, Verification error) {
        var row = current(job);
        row.status = Publication.PERMANENT_FAILURE;
        row.lastError = error;
        finish(row);
    }

    @Transactional
    public void confirm(Job job, Confirmation confirmation, Instant now) {
        var row = current(job);
        row.status = Publication.CONFIRMED;
        row.lastError = null;
        row.transactionHash = confirmation.hash();
        row.blockNumber = confirmation.block();
        row.blockHash = confirmation.blockHash();
        row.confirmedAt = now;
        finish(row);
    }

    private EvidenceOutboxEntity current(Job job) {
        var row = rows.findById(job.id()).orElseThrow();
        if (row.version != job.version()) throw new OptimisticLockingFailureException("La reserva de evidencia cambió");
        return row;
    }

    private Job save(EvidenceOutboxEntity row) {
        entities.flush();
        return snapshot(row);
    }

    private static void finish(EvidenceOutboxEntity row) {
        row.activeSlot = null;
        row.signedTransaction = null;
        row.nonce = null;
        row.nextAttemptAt = null;
    }

    private static String writerSlot(Target target, String writer) {
        return target.chainId() + ":" + writer.toLowerCase(Locale.ROOT);
    }

    private static Job snapshot(EvidenceOutboxEntity row) {
        var signed = row.signedTransaction == null ? null
                : new Signed(row.transactionHash, new BigInteger(row.nonce), row.signedTransaction);
        return new Job(row.evidence.toDomain(), row.version, row.status, row.attempts, row.lastError, row.nextAttemptAt,
                row.target(), signed, row.transactionHash, row.blockNumber, row.blockHash, row.confirmedAt);
    }
}
