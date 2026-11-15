package com.acme.backendfreshsense.traceability;

import com.acme.backendfreshsense.traceability.infrastructure.CanonicalEvidenceCodec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CanonicalEvidenceCodecTest {
    private static final String VECTOR = """
            {"businessEventId":"00000000-0000-0000-0000-000000000003","createdAt":"2026-11-15T12:00:00.123456Z","domain":"freshsense.trace.lot-registered","eventType":"LOT_REGISTERED","evidenceId":"00000000-0000-0000-0000-000000000002","expiresOn":null,"initialQuantity":"12.340","lotCode":"MANZANA-1","lotId":"00000000-0000-0000-0000-000000000001","lotStatus":"REGISTERED","ownerId":"9007199254740993","productCategory":"Frutas","productId":"7","productName":"Manzana Ñ","receivedAt":"2026-11-15T11:10:00.000000Z","schemaVersion":1,"source":"AUTHENTICATED_USER","unit":"KG"}
            """.stripTrailing();
    private static final String EXPECTED_SHA256 = "8bbc3d695b6015470f21dffbea94b86457cf81782a8095225678dcdad7fea9c8";

    private final CanonicalEvidenceCodec codec = new CanonicalEvidenceCodec();

    @Test
    void reproducesKnownUtf8Sha256Vector() {
        String encoded = codec.encode(codec.decode(VECTOR));
        assertThat(encoded).isEqualTo(VECTOR);
        assertThat(codec.sha256(encoded)).isEqualTo(EXPECTED_SHA256);
    }

    @Test
    void ignoresPropertyOrderAndWhitespaceButCommitsChangedValues() {
        String reordered = "{ \"unit\": \"KG\", " + VECTOR.substring(1, VECTOR.lastIndexOf(",\"unit\"")) + " }";
        assertThat(codec.sha256(codec.encode(codec.decode(reordered)))).isEqualTo(EXPECTED_SHA256);
        String changed = VECTOR.replace("12.340", "12.341");
        assertThat(codec.sha256(codec.encode(codec.decode(changed)))).isNotEqualTo(EXPECTED_SHA256);
    }

    @Test
    void rejectsAmbiguousJsonAndUsesJcsStringEscapes() {
        assertThatThrownBy(() -> codec.decode("{\"unit\":\"KG\"," + VECTOR.substring(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(VECTOR + "{}")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> codec.decode(VECTOR.replace("\"12.340\"", "12.340")))
                .isInstanceOf(IllegalArgumentException.class);
        var snapshot = codec.decode(VECTOR.replace("Manzana Ñ", "Manzana \\u000B Ñ"));
        assertThat(codec.encode(snapshot)).contains("Manzana \\u000b Ñ");
    }
}
