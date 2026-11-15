package com.acme.backendfreshsense.traceability.infrastructure.blockchain;

import com.acme.backendfreshsense.accounts.application.dto.UserRegistrationRequest;
import com.acme.backendfreshsense.accounts.application.service.AccountService;
import com.acme.backendfreshsense.inventory.application.dto.ProductRequest;
import com.acme.backendfreshsense.inventory.application.service.ProductService;
import com.acme.backendfreshsense.traceability.application.TraceabilityService;
import com.acme.backendfreshsense.traceability.domain.FoodLot;
import com.acme.backendfreshsense.traceability.infrastructure.blockchain.AnchoringStatus.ChainProblem;
import com.acme.backendfreshsense.traceability.infrastructure.persistence.EvidenceOutbox;
import com.acme.backendfreshsense.traceability.infrastructure.persistence.EvidenceOutbox.Confirmation;
import com.acme.backendfreshsense.traceability.infrastructure.persistence.EvidenceOutbox.Signed;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.acme.backendfreshsense.traceability.infrastructure.blockchain.AnchoringStatus.Publication.*;
import static com.acme.backendfreshsense.traceability.infrastructure.blockchain.AnchoringStatus.Verification.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest
class EvidencePublisherIntegrationTest {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired EvidenceOutbox outbox;
    @Autowired TraceabilityService traceability;
    @Autowired AccountService accounts;
    @Autowired ProductService inventory;

    private BlockchainSettings settings;
    private Web3jEvidenceChain chain;
    private Web3jEvidenceChain.Deployment deployment;
    private EvidenceOutbox.Job job;
    private final Signed signed = new Signed("0x" + "5".repeat(64), BigInteger.ONE, "0xaabb");

    @BeforeEach
    void setup() {
        String unique = UUID.randomUUID().toString().replace("-", "");
        settings = new BlockchainSettings(true, URI.create("http://127.0.0.1:8545"), 31337,
                "0x" + unique + "a".repeat(8), "0x" + unique.repeat(2), "0x" + unique + "b".repeat(8),
                UUID.randomUUID().toString().replace("-", "").repeat(2),
                Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(60));
        chain = mock(Web3jEvidenceChain.class);
        deployment = new Web3jEvidenceChain.Deployment(settings.target(), BigInteger.ONE, true);
        when(chain.deployment(settings.target())).thenReturn(deployment);
        var owner = accounts.register(new UserRegistrationRequest(
                "outbox-" + unique + "@example.test", "Lab9-" + unique, "Prueba Outbox")).getId();
        var product = inventory.create(owner, new ProductRequest("Manzana", null, "Frutas", 9, null,
                LocalDate.now().plusDays(3)));
        var lot = traceability.register(owner, product.id(), "OUTBOX", BigDecimal.ONE, FoodLot.Unit.KG, Instant.now(), null);
        var row = outbox.find(lot.initialEvidenceId()).orElseThrow();
        assertThat(row.status()).isEqualTo(PENDING);
        jdbc.update("""
                UPDATE trace_evidence_outbox
                SET target_key = ?, chain_id = ?, contract_address = ?, deployment_transaction = ?
                WHERE evidence_id = UUID_TO_BIN(?)
                """, settings.target().key(), settings.target().chainId(), settings.target().contract(),
                settings.target().deploymentTransaction(), row.id().toString());
        job = row;
        due();
        when(chain.lookup(deployment, job.evidence())).thenReturn(new Web3jEvidenceChain.OnChain(false, false, false));
        when(chain.prepare(deployment, job.evidence())).thenReturn(signed);
    }

    @Test
    void recoversAfterRpcFailureAndAnAmbiguousBroadcastUsingDurableState() {
        when(chain.deployment(settings.target())).thenThrow(new ChainProblem(RPC_UNAVAILABLE)).thenReturn(deployment);
        Instant beforeAttempt = Instant.now();
        publisher().runOnce();
        var first = stored();
        assertThat(first.status()).isEqualTo(RETRYABLE_FAILURE);
        assertThat(first.lastError()).isEqualTo(RPC_UNAVAILABLE);
        assertThat(first.attempts()).isEqualTo(1);
        assertThat(first.nextAttemptAt()).isAfter(beforeAttempt.plusSeconds(1));

        due();
        doAnswer(invocation -> {
            assertThat(stored().status()).isEqualTo(PREPARED);
            assertThat(stored().signed()).isEqualTo(signed);
            throw new ChainProblem(RPC_UNAVAILABLE);
        }).when(chain).broadcast(signed);
        publisher().runOnce();
        assertThat(stored().status()).isEqualTo(PREPARED);
        assertThat(stored().attempts()).isEqualTo(2);

        due();
        when(chain.lookup(deployment, job.evidence())).thenReturn(new Web3jEvidenceChain.OnChain(true, true, true));
        String blockHash = "0x" + "9".repeat(64);
        when(chain.reconcile(deployment, job.evidence())).thenReturn(Optional.of(new Confirmation(signed.hash(), 8, blockHash)));
        publisher().runOnce();
        var confirmed = stored();
        assertThat(confirmed.status()).isEqualTo(CONFIRMED);
        assertThat(confirmed.blockNumber()).isEqualTo(8);
        assertThat(confirmed.confirmedAt()).isNotNull();
        assertThat(confirmed.signed()).isNull();
        verify(chain, times(1)).broadcast(signed);
    }

    @Test
    void retainsPendingNonceAndResendsTheExactBytesOnlyAfterTheHashDisappears() {
        publisher().runOnce();
        assertThat(stored().status()).isEqualTo(SUBMITTED);
        var anotherContract = new BlockchainSettings.Target(31337, "0x" + "a".repeat(40), "0x" + "c".repeat(64));
        assertThat(outbox.next(anotherContract, settings.writerAddress(), Instant.now().plusSeconds(60))).isEmpty();
        due();
        when(chain.transactionKnown(signed.hash())).thenReturn(true, false);
        publisher().runOnce();
        verify(chain, times(1)).broadcast(signed);
        assertThat(stored().signed()).isEqualTo(signed);

        due();
        publisher().runOnce();
        verify(chain, times(2)).broadcast(signed);
        verify(chain, times(1)).prepare(deployment, job.evidence());
        assertThat(stored().transactionHash()).isEqualTo(signed.hash());
    }

    @Test
    void electsOnlyOnePublisherForTheMysqlSessionLock() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(chain.lookup(deployment, job.evidence())).thenAnswer(ignored -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return new Web3jEvidenceChain.OnChain(false, false, false);
        });
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> publisher().runOnce());
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            pool.submit(() -> publisher().runOnce()).get(5, TimeUnit.SECONDS);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(stored().attempts()).isEqualTo(1);
        verify(chain).broadcast(signed);
    }

    @Test
    void stopsOnAnIncompatibleExistingEvidenceAndCapsRetryDelay() {
        when(chain.lookup(deployment, job.evidence())).thenReturn(new Web3jEvidenceChain.OnChain(true, true, false));
        publisher().runOnce();
        assertThat(stored().status()).isEqualTo(PERMANENT_FAILURE);
        assertThat(stored().lastError()).isEqualTo(EVIDENCE_CONFLICT);
        verify(chain, never()).broadcast(any());
        assertThat(settings.retryDelay(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(1));
    }

    private EvidencePublisher publisher() {
        return new EvidencePublisher(settings, outbox, chain, dataSource);
    }

    private EvidenceOutbox.Job stored() {
        return outbox.find(job.id()).orElseThrow();
    }

    private void due() {
        jdbc.update("UPDATE trace_evidence_outbox SET next_attempt_at = '2000-01-01' WHERE evidence_id = UUID_TO_BIN(?)",
                job.id().toString());
    }
}
