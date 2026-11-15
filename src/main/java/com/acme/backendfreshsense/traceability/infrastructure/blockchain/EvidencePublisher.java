package com.acme.backendfreshsense.traceability.infrastructure.blockchain;

import com.acme.backendfreshsense.traceability.infrastructure.blockchain.AnchoringStatus.ChainProblem;
import com.acme.backendfreshsense.traceability.infrastructure.persistence.EvidenceOutbox;
import com.acme.backendfreshsense.traceability.infrastructure.persistence.EvidenceOutbox.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;

import static com.acme.backendfreshsense.traceability.infrastructure.blockchain.AnchoringStatus.Verification.*;

class EvidencePublisher {
    private static final Logger log = LoggerFactory.getLogger(EvidencePublisher.class);
    private static final String DB_LOCK = "CONCAT('freshsense.traceability.', MD5(DATABASE()))";

    private final BlockchainSettings settings;
    private final EvidenceOutbox outbox;
    private final Web3jEvidenceChain chain;
    private final DataSource dataSource;

    EvidencePublisher(BlockchainSettings settings, EvidenceOutbox outbox, Web3jEvidenceChain chain,
                      DataSource dataSource) {
        this.settings = settings;
        this.outbox = outbox;
        this.chain = chain;
        this.dataSource = dataSource;
    }

    @Scheduled(fixedDelayString = "${freshsense.blockchain.poll-interval:PT2S}", scheduler = "traceabilityBlockchainScheduler")
    void runOnce() {
        // A MySQL session lock spans the RPCs, while each outbox write has its own short transaction.
        // MySQL releases the lock on disconnect. The signed bytes and the active nonce survive independently.
        try (var session = dataSource.getConnection()) {
            if (!lock(session, "SELECT GET_LOCK(" + DB_LOCK + ", 0)")) return;
            try {
                Instant now = Instant.now();
                var target = settings.target();
                var next = outbox.next(target, settings.writerAddress(), now);
                if (next.isEmpty()) return;
                var job = next.get();
                var attempted = outbox.attempt(job, target, now.plus(settings.retryDelay(
                        job.attempts() == Integer.MAX_VALUE ? job.attempts() : job.attempts() + 1)));
                publish(attempted);
            } finally {
                release(session);
            }
        } catch (SQLException | DataAccessException e) {
            log.warn("No se pudo actualizar la publicación de trazabilidad ({})", e.getClass().getSimpleName());
        }
    }

    private void publish(Job job) {
        try {
            var deployment = chain.deployment(job.target());
            var observed = chain.lookup(deployment, job.evidence());
            if (observed.exists()) {
                if (!observed.matches()) {
                    outbox.fail(job, EVIDENCE_CONFLICT);
                    return;
                }
                var confirmation = chain.reconcile(deployment, job.evidence());
                if (confirmation.isPresent()) {
                    outbox.confirm(job, confirmation.get(), Instant.now());
                } else {
                    outbox.retry(job, RECEIPT_INVALID);
                }
                return;
            }
            if (job.signed() == null) {
                // Commit the nonce, hash and signed bytes before making any broadcast RPC.
                job = outbox.prepare(job, chain.prepare(deployment, job.evidence()), settings.writerAddress());
            } else if (!canResend(job)) {
                return;
            }
            chain.broadcast(job.signed());
            outbox.submitted(job);
        } catch (ChainProblem e) {
            // A role failure is definitive for this preflight. An RPC error leaves broadcast ambiguous.
            if (e.code() == WRITER_UNAUTHORIZED && job.signed() == null) outbox.fail(job, e.code());
            else outbox.retry(job, e.code());
        }
    }

    private boolean canResend(Job job) {
        var receipt = chain.receipt(job.transactionHash());
        if (receipt.isPresent()) {
            if (Web3jEvidenceChain.successful(receipt.get())) outbox.retry(job, EVIDENCE_MISSING);
            else outbox.fail(job, TRANSACTION_REVERTED);
            return false;
        }
        if (chain.transactionKnown(job.transactionHash())) {
            outbox.submitted(job);
            return false;
        }
        if (chain.nonceConsumed(job.signed())) {
            // Confirm the absence on another pass before releasing a nonce consumed after the first state read.
            if (job.lastError() == NONCE_CONSUMED) outbox.fail(job, NONCE_CONSUMED);
            else outbox.retry(job, NONCE_CONSUMED);
            return false;
        }
        return true;
    }

    private static boolean lock(Connection session, String sql) throws SQLException {
        try (var statement = session.createStatement(); var result = statement.executeQuery(sql)) {
            return result.next() && result.getInt(1) == 1;
        }
    }

    private static void release(Connection session) throws SQLException {
        try {
            lock(session, "SELECT RELEASE_LOCK(" + DB_LOCK + ")");
        } catch (SQLException failure) {
            // A pooled connection must never be returned while it might still own the session lock.
            session.abort(Runnable::run);
            throw failure;
        }
    }
}
