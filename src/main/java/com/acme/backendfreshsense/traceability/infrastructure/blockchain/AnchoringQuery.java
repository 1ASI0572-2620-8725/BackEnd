package com.acme.backendfreshsense.traceability.infrastructure.blockchain;

import com.acme.backendfreshsense.traceability.application.TraceabilityService;
import com.acme.backendfreshsense.traceability.infrastructure.blockchain.AnchoringStatus.ChainProblem;
import com.acme.backendfreshsense.traceability.infrastructure.blockchain.AnchoringStatus.Publication;
import com.acme.backendfreshsense.traceability.infrastructure.blockchain.AnchoringStatus.Verification;
import com.acme.backendfreshsense.traceability.infrastructure.blockchain.BlockchainSettings.Target;
import com.acme.backendfreshsense.traceability.infrastructure.persistence.EvidenceOutbox;
import com.acme.backendfreshsense.traceability.infrastructure.persistence.EvidenceOutbox.Job;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

import static com.acme.backendfreshsense.traceability.infrastructure.blockchain.AnchoringStatus.Verification.*;

@Service
public class AnchoringQuery {
    private final TraceabilityService traceability;
    private final EvidenceOutbox outbox;
    private final ObjectProvider<Web3jEvidenceChain> chains;

    AnchoringQuery(TraceabilityService traceability, EvidenceOutbox outbox, ObjectProvider<Web3jEvidenceChain> chains) {
        this.traceability = traceability;
        this.outbox = outbox;
        this.chains = chains;
    }

    public record Response(UUID evidenceId, Publication publicationStatus, boolean localMatches,
                           Verification blockchainVerification, Boolean onChainExists, Boolean onChainDigestMatches,
                           Boolean onChainMetadataMatches, Long chainId, String contractAddress,
                           String deploymentTransaction, String transactionHash, Long blockNumber, String blockHash,
                           Instant confirmedAt, int attempts, Verification lastError, Instant nextAttemptAt) {}

    public Response owned(long ownerId, UUID evidenceId) {
        boolean local = traceability.verify(ownerId, evidenceId);
        Job job = outbox.find(evidenceId).orElse(null);
        var chain = chains.getIfAvailable();
        Target target = job == null ? null : job.target();
        if (chain == null) return response(evidenceId, local, job, DISABLED, null);
        if (target == null) return response(evidenceId, local, job, NO_TARGET, null);

        Web3jEvidenceChain.OnChain current = null;
        Verification verification;
        try {
            var deployment = chain.deployment(target);
            current = chain.lookup(deployment, job.evidence());
            verification = verify(chain, deployment, job, current);
        } catch (ChainProblem e) {
            verification = e.code();
        }
        return response(evidenceId, local, job, verification, current);
    }

    private Verification verify(Web3jEvidenceChain chain, Web3jEvidenceChain.Deployment deployment, Job job,
                                Web3jEvidenceChain.OnChain observed) {
        if (observed.exists() && !observed.matches()) return EVIDENCE_CONFLICT;
        if (job.transactionHash() == null) return observed.exists() ? ON_CHAIN_PRESENT : EVIDENCE_MISSING;
        var receipt = chain.receipt(job.transactionHash());
        if (receipt.isEmpty()) {
            return chain.transactionKnown(job.transactionHash()) ? TRANSACTION_PENDING : TRANSACTION_MISSING;
        }
        if (!observed.exists()) return EVIDENCE_MISSING;
        var proof = chain.confirmation(receipt.get(), deployment, job.evidence());
        if (proof.isEmpty()) return RECEIPT_INVALID;
        if (job.blockNumber() != null && (!job.blockNumber().equals(proof.get().block())
                || !job.blockHash().equalsIgnoreCase(proof.get().blockHash()))) return RECEIPT_INVALID;
        return VERIFIED;
    }

    private Response response(UUID id, boolean local, Job job, Verification verification,
                              Web3jEvidenceChain.OnChain observed) {
        if (job == null) {
            return new Response(id, Publication.NOT_QUEUED, local, verification, null, null, null,
                    null, null, null, null, null, null, null, 0, null, null);
        }
        var target = job.target();
        return new Response(id, job.status(), local, verification, observed == null ? null : observed.exists(),
                observed == null ? null : observed.digestMatches(), observed == null ? null : observed.metadataMatches(),
                target == null ? null : target.chainId(), target == null ? null : target.contract(),
                target == null ? null : target.deploymentTransaction(), job.transactionHash(), job.blockNumber(),
                job.blockHash(), job.confirmedAt(), job.attempts(), job.lastError(), job.nextAttemptAt());
    }
}
