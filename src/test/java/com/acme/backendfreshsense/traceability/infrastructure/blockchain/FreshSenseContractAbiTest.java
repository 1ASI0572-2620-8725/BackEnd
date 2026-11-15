package com.acme.backendfreshsense.traceability.infrastructure.blockchain;

import com.acme.backendfreshsense.traceability.domain.TraceEvidence;
import org.junit.jupiter.api.Test;
import org.web3j.abi.FunctionEncoder;
import org.web3j.utils.Numeric;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class FreshSenseContractAbiTest {
    @Test
    void preservesUuidNetworkOrderAndSha256LeadingZeros() {
        var evidenceId = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        var lot = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        var digest = "00" + "ab".repeat(31);
        var evidence = new TraceEvidence(evidenceId, lot, UUID.randomUUID(), TraceEvidence.Type.LOT_REGISTERED,
                1, "test", digest, Instant.EPOCH, TraceEvidence.Source.AUTHENTICATED_USER);
        assertThat(Numeric.toHexString(FreshSenseContractAbi.uuid(evidenceId).getValue()))
                .isEqualTo("0x00112233445566778899aabbccddeeff");
        assertThat(Numeric.toHexString(FreshSenseContractAbi.digest("0x" + digest).getValue())).isEqualTo("0x" + digest);
        assertThat(FunctionEncoder.encode(FreshSenseContractAbi.record(evidence)).substring(10)).isEqualTo(
                "00112233445566778899aabbccddeeff" + "0".repeat(32) + "f".repeat(32) + "0".repeat(32)
                        + digest + ("0".repeat(63) + "1").repeat(2));
    }

    @Test
    void rejectsNullUuidAndDigestsOfTheWrongWidth() {
        assertThatIllegalArgumentException().isThrownBy(() -> FreshSenseContractAbi.uuid(new UUID(0, 0)));
        assertThatIllegalArgumentException().isThrownBy(() -> FreshSenseContractAbi.digest("a".repeat(63)));
        assertThatIllegalArgumentException().isThrownBy(() -> FreshSenseContractAbi.digest("g".repeat(64)));
    }
}
