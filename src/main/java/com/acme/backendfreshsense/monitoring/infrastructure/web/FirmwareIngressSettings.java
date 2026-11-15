package com.acme.backendfreshsense.monitoring.infrastructure.web;

import com.acme.backendfreshsense.monitoring.infrastructure.persistence.jpa.SensorObservation.Source;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

@ConfigurationProperties("freshsense.iot")
public record FirmwareIngressSettings(List<Binding> bindings) {
    public FirmwareIngressSettings {
        bindings = bindings == null ? List.of() : List.copyOf(bindings);
        var ids = new HashSet<String>();
        var tokens = new HashSet<String>();
        for (var binding : bindings) {
            if (!ids.add(binding.deviceId()) || !tokens.add(binding.token())) {
                throw new IllegalArgumentException("Cada dispositivo IoT requiere una credencial exclusiva");
            }
        }
    }

    Optional<Binding> authenticate(String deviceId, String token) {
        if (token == null) return Optional.empty();
        var incoming = token.getBytes(StandardCharsets.UTF_8);
        return bindings.stream().filter(binding -> binding.deviceId().equals(deviceId)
                && MessageDigest.isEqual(binding.token().getBytes(StandardCharsets.UTF_8), incoming)).findFirst();
    }

    public record Binding(String deviceId, long deviceKey, String token, Source source, ZoneId zone, String profile) {
        public Binding {
            if (deviceId == null || deviceId.isBlank() || deviceKey <= 0 || token == null || token.isBlank()
                    || (source != Source.DEVICE_UNVERIFIED && source != Source.AUTHENTICATED_GATEWAY && source != Source.SIMULATED)
                    || (profile != null && !"FS-FRUIT-1".equals(profile))) {
                throw new IllegalArgumentException("Asignación de credencial o perfil IoT inválido");
            }
        }

        @Override
        public String toString() {
            return "Binding[deviceId=" + deviceId + ", source=" + source + "]";
        }
    }
}
