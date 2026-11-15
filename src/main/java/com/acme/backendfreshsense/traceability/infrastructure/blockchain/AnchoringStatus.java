package com.acme.backendfreshsense.traceability.infrastructure.blockchain;

public final class AnchoringStatus {
    private AnchoringStatus() {}

    public enum Publication {
        NOT_QUEUED, PENDING, PREPARED, SUBMITTED, CONFIRMED, RETRYABLE_FAILURE, PERMANENT_FAILURE
    }

    public enum Verification {
        DISABLED, NO_TARGET, VERIFIED, ON_CHAIN_PRESENT, EVIDENCE_MISSING, EVIDENCE_CONFLICT,
        TRANSACTION_PENDING, TRANSACTION_MISSING, RECEIPT_INVALID, RPC_UNAVAILABLE,
        CHAIN_MISMATCH, CONTRACT_MISSING, CONTRACT_INCOMPATIBLE, DEPLOYMENT_MISSING,
        WRITER_UNAUTHORIZED, TRANSACTION_REVERTED, NONCE_CONSUMED, RPC_REJECTED
    }

    public static final class ChainProblem extends RuntimeException {
        private final Verification code;

        public ChainProblem(Verification code) {
            super(code.name());
            this.code = code;
        }

        public Verification code() {
            return code;
        }
    }
}
