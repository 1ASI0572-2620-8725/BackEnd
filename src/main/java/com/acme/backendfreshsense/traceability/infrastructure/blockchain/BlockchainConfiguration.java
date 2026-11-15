package com.acme.backendfreshsense.traceability.infrastructure.blockchain;

import com.acme.backendfreshsense.traceability.infrastructure.persistence.EvidenceOutbox;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import javax.sql.DataSource;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(BlockchainSettings.class)
class BlockchainConfiguration {
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "freshsense.blockchain.enabled", havingValue = "true")
    static class EnabledBlockchain {
        @Bean
        Web3jEvidenceChain evidenceChain(BlockchainSettings settings) {
            return new Web3jEvidenceChain(settings);
        }

        @Bean
        EvidencePublisher evidencePublisher(BlockchainSettings settings, EvidenceOutbox outbox,
                                            Web3jEvidenceChain chain, DataSource dataSource) {
            return new EvidencePublisher(settings, outbox, chain, dataSource);
        }

        @Bean(name = "traceabilityBlockchainScheduler", defaultCandidate = false)
        ThreadPoolTaskScheduler traceabilityBlockchainScheduler() {
            var scheduler = new ThreadPoolTaskScheduler();
            scheduler.setThreadNamePrefix("traceability-evm-");
            return scheduler;
        }
    }
}
