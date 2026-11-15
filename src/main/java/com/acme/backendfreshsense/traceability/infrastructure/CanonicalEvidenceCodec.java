package com.acme.backendfreshsense.traceability.infrastructure;

import com.acme.backendfreshsense.traceability.application.EvidenceCodec;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.core.json.JsonWriteFeature;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
public class CanonicalEvidenceCodec implements EvidenceCodec {
    // Schema v1 uses an object with ASCII keys and only strings, null and an integer version.
    private final JsonMapper json = JsonMapper.builder()
            .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .disable(JsonWriteFeature.WRITE_HEX_UPPER_CASE)
            .disable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .build();

    @Override
    public String encode(LotRegisteredSnapshot snapshot) {
        try {
            String encoded = json.writeValueAsString(snapshot);
            if (!StandardCharsets.UTF_8.newEncoder().canEncode(encoded)) {
                throw new IllegalStateException("La evidencia contiene texto Unicode inválido");
            }
            return encoded;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar la evidencia", e);
        }
    }

    @Override
    public LotRegisteredSnapshot decode(String snapshot) {
        try {
            JsonNode object = json.readTree(snapshot);
            if (object == null || !object.isObject() || !object.path("schemaVersion").isInt()) {
                throw new IllegalArgumentException("Instantánea de evidencia inválida");
            }
            for (var field : object.properties()) {
                var value = field.getValue();
                if (field.getKey().equals("schemaVersion") || (field.getKey().equals("expiresOn") && value.isNull())) {
                    continue;
                }
                if (!value.isTextual() || !StandardCharsets.UTF_8.newEncoder().canEncode(value.textValue())) {
                    throw new IllegalArgumentException("Instantánea de evidencia inválida");
                }
            }
            return json.treeToValue(object, LotRegisteredSnapshot.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Instantánea de evidencia inválida", e);
        }
    }

    @Override
    public String sha256(String canonicalSnapshot) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonicalSnapshot.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }
}
