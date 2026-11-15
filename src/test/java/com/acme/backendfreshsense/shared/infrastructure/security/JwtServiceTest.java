package com.acme.backendfreshsense.shared.infrastructure.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    @Test
    void givesEveryRefreshEmissionADistinctSignedIdentifier() {
        String signingKey = UUID.randomUUID().toString();
        JwtService jwtService = new JwtService(signingKey, 15, 7);
        JwtParser parser = Jwts.parser()
                .verifyWith(Keys.hmacShaKeyFor(signingKey.getBytes(StandardCharsets.UTF_8)))
                .build();
        Set<String> ids = new HashSet<>();

        for (int i = 0; i < 32; i++) {
            Claims claims = parser.parseSignedClaims(
                    jwtService.generateRefreshToken("refresh@example.test", 42L)).getPayload();

            assertThat(claims.getId()).isNotBlank();
            assertThat(ids.add(claims.getId())).as("jti for issuance %s is unique", i).isTrue();
            assertThat(claims.getSubject()).isEqualTo("refresh@example.test");
            assertThat(claims.get("type")).isEqualTo("refresh");
            assertThat(claims.getExpiration()).isAfter(claims.getIssuedAt());
        }
    }
}
