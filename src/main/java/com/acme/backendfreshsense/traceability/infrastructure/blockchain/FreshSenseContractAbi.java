package com.acme.backendfreshsense.traceability.infrastructure.blockchain;

import com.acme.backendfreshsense.traceability.domain.TraceEvidence;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.Bytes16;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.abi.datatypes.generated.Uint32;
import org.web3j.utils.Numeric;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.UUID;

final class FreshSenseContractAbi {
    // Read from the Hardhat FreshSenseTraceability artifact, solc 0.8.38, Cancun.
    static final String RUNTIME_CODE_HASH = "0xc2294d6220a230d3fa4742d61c6060bb8a62405ddb5e29ac651369bf08c62d0a";
    static final String RECORDED_TOPIC = EventEncoder.encode(new Event("EvidenceRecorded", List.of(
            new TypeReference<Bytes16>(true) {}, new TypeReference<Bytes16>(true) {},
            new TypeReference<Bytes32>() {}, new TypeReference<Uint8>() {}, new TypeReference<Uint32>() {})));

    private FreshSenseContractAbi() {}

    static Bytes16 uuid(UUID value) {
        if (value.getMostSignificantBits() == 0 && value.getLeastSignificantBits() == 0) {
            throw new IllegalArgumentException("UUID nulo");
        }
        return new Bytes16(ByteBuffer.allocate(16).putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits()).array());
    }

    static Bytes32 digest(String hex) {
        if (hex == null || !hex.matches("(?i)(?:0x)?[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Se requieren 32 bytes SHA-256");
        }
        return new Bytes32(Numeric.hexStringToByteArray(hex.toLowerCase(java.util.Locale.ROOT)));
    }

    static Uint8 eventType(TraceEvidence evidence) {
        return switch (evidence.type()) {
            case LOT_REGISTERED -> new Uint8(1);
        };
    }

    static Function record(TraceEvidence e) {
        return new Function("recordEvidence", List.of(uuid(e.id()), uuid(e.lotId()), digest(e.sha256()),
                eventType(e), new Uint32(e.schemaVersion())), List.of());
    }

    static Function get(UUID id) {
        return new Function("getEvidence", List.of(uuid(id)), List.of(new TypeReference<EvidenceTuple>() {}));
    }

    static Function verify(TraceEvidence e) {
        return new Function("verifyEvidence", List.of(uuid(e.id()), digest(e.sha256())),
                List.of(new TypeReference<Bool>() {}, new TypeReference<Bool>() {}));
    }

    static Function writerRole() {
        return new Function("EVIDENCE_WRITER_ROLE", List.of(), List.of(new TypeReference<Bytes32>() {}));
    }

    static Function hasRole(Bytes32 role, String writer) {
        return new Function("hasRole", List.of(role, new Address(writer)), List.of(new TypeReference<Bool>() {}));
    }

    public static final class EvidenceTuple extends StaticStruct {
        final Bytes16 lotId;
        final Uint8 eventType;
        final Uint32 schemaVersion;
        final Bool exists;
        final Bytes32 digest;

        public EvidenceTuple(Bytes16 lotId, Uint8 eventType, Uint32 schemaVersion, Bool exists, Bytes32 digest) {
            super(lotId, eventType, schemaVersion, exists, digest);
            this.lotId = lotId;
            this.eventType = eventType;
            this.schemaVersion = schemaVersion;
            this.exists = exists;
            this.digest = digest;
        }
    }
}
