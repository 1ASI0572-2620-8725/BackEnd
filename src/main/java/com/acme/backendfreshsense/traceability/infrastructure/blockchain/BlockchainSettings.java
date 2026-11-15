package com.acme.backendfreshsense.traceability.infrastructure.blockchain;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;

@ConfigurationProperties("freshsense.blockchain")
public record BlockchainSettings(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://127.0.0.1:8545") URI rpcUrl,
        @DefaultValue("31337") long chainId,
        String contractAddress,
        String deploymentTransaction,
        String writerAddress,
        String writerPrivateKey,
        @DefaultValue("PT3S") Duration rpcTimeout,
        @DefaultValue("PT2S") Duration retryInitial,
        @DefaultValue("PT1M") Duration retryMax) {

    public BlockchainSettings {
        if (chainId <= 0 || rpcTimeout.isNegative() || rpcTimeout.isZero()
                || retryInitial.compareTo(Duration.ofMillis(1)) < 0 || retryMax.compareTo(retryInitial) < 0) {
            throw new IllegalArgumentException("Configuración de tiempos o chain ID inválida para trazabilidad");
        }
        if (enabled) {
            new Target(chainId, contractAddress, deploymentTransaction);
            if (rpcUrl.getHost() == null || rpcUrl.getUserInfo() != null
                    || !("http".equals(rpcUrl.getScheme()) || "https".equals(rpcUrl.getScheme()))
                    || !validHex(writerAddress, 40) || writerPrivateKey == null
                    || !writerPrivateKey.matches("(?:0x)?[0-9a-fA-F]{64}")) {
                throw new IllegalArgumentException("Configuración RPC o credencial de escritura inválida para trazabilidad");
            }
        }
    }

    public Target target() {
        return enabled ? new Target(chainId, contractAddress, deploymentTransaction) : null;
    }

    public Duration retryDelay(int attempts) {
        long multiplier = 1L << Math.min(Math.max(attempts - 1, 0), 20);
        Duration delay = retryInitial.multipliedBy(multiplier);
        return delay.compareTo(retryMax) < 0 ? delay : retryMax;
    }

    @Override
    public String toString() {
        return "BlockchainSettings[enabled=" + enabled + ", chainId=" + chainId + "]";
    }

    public record Target(long chainId, String contract, String deploymentTransaction) {
        public Target {
            if (chainId <= 0 || !validHex(contract, 40) || !validHex(deploymentTransaction, 64)) {
                throw new IllegalArgumentException("Se requieren dirección de contrato y transacción de despliegue válidas");
            }
            contract = contract.toLowerCase(Locale.ROOT);
            deploymentTransaction = deploymentTransaction.toLowerCase(Locale.ROOT);
        }

        public String key() {
            return chainId + ":" + contract + ":" + deploymentTransaction;
        }
    }

    private static boolean validHex(String value, int width) {
        return value != null && value.matches("0x[0-9a-fA-F]{" + width + "}") && !value.substring(2).matches("0+");
    }
}
