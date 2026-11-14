package com.acme.backendfreshsense.monitoring.infrastructure.web;

import com.acme.backendfreshsense.monitoring.application.dto.ClaimResponse;
import com.acme.backendfreshsense.monitoring.application.dto.MonitoringReadingDto;
import com.acme.backendfreshsense.monitoring.application.service.DeviceService;
import com.acme.backendfreshsense.monitoring.application.service.MonitoringService;
import com.acme.backendfreshsense.monitoring.domain.model.Device;
import com.acme.backendfreshsense.shared.infrastructure.security.JwtService;
import com.acme.backendfreshsense.shared.infrastructure.security.OAuth2SuccessHandler;
import com.acme.backendfreshsense.shared.infrastructure.security.RateLimitFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integración del controlador Edge (capa web):
 * POST /api/edge/readings con autenticación por X-Device-Key.
 */
@WebMvcTest(controllers = EdgeController.class)
@AutoConfigureMockMvc(addFilters = false)
class EdgeControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DeviceService deviceService;

    @MockBean
    private MonitoringService monitoringService;

    @MockBean
    private JwtService jwtService;

    @MockBean
    private RateLimitFilter rateLimitFilter;

    @MockBean
    private OAuth2SuccessHandler oAuth2SuccessHandler;

    @Test
    @DisplayName("POST /api/edge/readings acepta lectura autenticada y responde 201")
    void ingestAuthorizedReadingReturnsCreated() throws Exception {
        Device device = new Device();
        device.setUserId(10L);
        device.setDeviceId("esp32-freshsense-1");

        when(deviceService.authenticate("esp32-freshsense-1", "secret-ok")).thenReturn(device);
        when(monitoringService.saveFromEdge(
                eq(10L), eq("esp32-freshsense-1"), eq("reading-001"),
                eq(6.4), eq(92.0), eq(0.5), any(LocalDateTime.class),
                eq("GREEN"), eq("Frutas")
        )).thenReturn(new MonitoringReadingDto(
                1L,
                6.4,
                92.0,
                0.5,
                null,
                null,
                null,
                LocalDateTime.of(2026, 10, 28, 23, 40),
                "esp32-freshsense-1",
                "GREEN",
                "Frutas"
        ));

        String body = """
                {
                  "deviceId": "esp32-freshsense-1",
                  "temperature": 6.4,
                  "humidity": 92.0,
                  "ethylenePpm": 0.5,
                  "time": "28/10/2026 23:40",
                  "id": "reading-001",
                  "status": "GREEN",
                  "category": "Frutas"
                }
                """;

        mockMvc.perform(post("/api/edge/readings")
                        .header("X-Device-Key", "secret-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.deviceId").value("esp32-freshsense-1"))
                .andExpect(jsonPath("$.status").value("GREEN"));
    }

    @Test
    @DisplayName("POST /api/edge/readings sin clave válida responde 401")
    void ingestUnauthorizedReadingReturns401() throws Exception {
        when(deviceService.authenticate("esp32-freshsense-1", "bad")).thenReturn(null);

        String body = """
                {
                  "deviceId": "esp32-freshsense-1",
                  "temperature": 6.4,
                  "humidity": 92.0,
                  "ethylenePpm": 0.5,
                  "time": "28/10/2026 23:40",
                  "id": "reading-001"
                }
                """;

        mockMvc.perform(post("/api/edge/readings")
                        .header("X-Device-Key", "bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/edge/readings sin etileno responde 400")
    void ingestMissingEthyleneReturns400() throws Exception {
        String body = """
                {
                  "deviceId": "esp32-freshsense-1",
                  "temperature": 6.4,
                  "humidity": 92.0,
                  "time": "28/10/2026 23:40",
                  "id": "reading-001"
                }
                """;

        mockMvc.perform(post("/api/edge/readings")
                        .header("X-Device-Key", "secret-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/edge/readings sin deviceId responde 400")
    void ingestMissingDeviceIdReturns400() throws Exception {
        String body = """
                {
                  "temperature": 6.4,
                  "humidity": 92.0,
                  "ethylenePpm": 0.5,
                  "time": "28/10/2026 23:40",
                  "id": "reading-001"
                }
                """;

        mockMvc.perform(post("/api/edge/readings")
                        .header("X-Device-Key", "secret-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/edge/readings sin humedad responde 400")
    void ingestMissingHumidityReturns400() throws Exception {
        String body = """
                {
                  "deviceId": "esp32-freshsense-1",
                  "temperature": 6.4,
                  "ethylenePpm": 0.5,
                  "time": "28/10/2026 23:40",
                  "id": "reading-001"
                }
                """;

        mockMvc.perform(post("/api/edge/readings")
                        .header("X-Device-Key", "secret-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("POST /api/edge/readings sin cabecera X-Device-Key responde 401")
    void ingestMissingDeviceKeyReturns401() throws Exception {
        when(deviceService.authenticate("esp32-freshsense-1", null)).thenReturn(null);

        String body = """
                {
                  "deviceId": "esp32-freshsense-1",
                  "temperature": 6.4,
                  "humidity": 92.0,
                  "ethylenePpm": 0.5,
                  "time": "28/10/2026 23:40",
                  "id": "reading-001"
                }
                """;

        mockMvc.perform(post("/api/edge/readings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("POST /api/edge/readings guarda lectura ROJA autenticada")
    void ingestAuthorizedRedReadingReturnsCreated() throws Exception {
        Device device = new Device();
        device.setUserId(10L);
        device.setDeviceId("esp32-freshsense-1");

        when(deviceService.authenticate("esp32-freshsense-1", "secret-ok")).thenReturn(device);
        when(monitoringService.saveFromEdge(
                eq(10L), eq("esp32-freshsense-1"), eq("reading-red"),
                eq(24.0), eq(40.0), eq(8.0), any(LocalDateTime.class),
                eq("RED"), eq("Frutas")
        )).thenReturn(new MonitoringReadingDto(
                2L,
                24.0,
                40.0,
                8.0,
                null,
                null,
                null,
                LocalDateTime.of(2026, 10, 28, 23, 45),
                "esp32-freshsense-1",
                "RED",
                "Frutas"
        ));

        String body = """
                {
                  "deviceId": "esp32-freshsense-1",
                  "temperature": 24.0,
                  "humidity": 40.0,
                  "ethylenePpm": 8.0,
                  "time": "28/10/2026 23:45",
                  "id": "reading-red",
                  "status": "RED",
                  "category": "Frutas"
                }
                """;

        mockMvc.perform(post("/api/edge/readings")
                        .header("X-Device-Key", "secret-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RED"))
                .andExpect(jsonPath("$.ethyleneLevel").value(8.0));
    }

    @Test
    @DisplayName("POST /api/edge/claim canjea código y entrega secretKey")
    void claimPairingCodeReturnsSecret() throws Exception {
        when(deviceService.claim("7K4Q2P"))
                .thenReturn(new ClaimResponse("esp32-freshsense-1", "secret-from-claim"));

        String body = """
                { "code": "7K4Q2P" }
                """;

        mockMvc.perform(post("/api/edge/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deviceId").value("esp32-freshsense-1"))
                .andExpect(jsonPath("$.secretKey").value("secret-from-claim"));
    }

    @Test
    @DisplayName("POST /api/edge/claim sin código responde 400")
    void claimBlankCodeReturns400() throws Exception {
        String body = """
                { "code": "  " }
                """;

        mockMvc.perform(post("/api/edge/claim")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
