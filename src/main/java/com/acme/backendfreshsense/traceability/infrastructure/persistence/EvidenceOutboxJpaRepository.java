package com.acme.backendfreshsense.traceability.infrastructure.persistence;

import com.acme.backendfreshsense.traceability.infrastructure.blockchain.AnchoringStatus.Publication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface EvidenceOutboxJpaRepository extends JpaRepository<EvidenceOutboxEntity, UUID> {
    Optional<EvidenceOutboxEntity> findByActiveSlot(String activeSlot);

    @Query("""
            select o from EvidenceOutboxEntity o
            where (o.targetKey is null or o.targetKey = :target)
              and o.status in :statuses and o.nextAttemptAt <= :now
            order by o.nextAttemptAt, o.id
            """)
    List<EvidenceOutboxEntity> due(String target, Collection<Publication> statuses, Instant now, Pageable limit);
}
