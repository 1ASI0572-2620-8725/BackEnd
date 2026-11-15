package com.acme.backendfreshsense.monitoring.infrastructure.web;

import com.acme.backendfreshsense.accounts.application.dto.UserRegistrationRequest;
import com.acme.backendfreshsense.accounts.application.service.AccountService;
import com.acme.backendfreshsense.inventory.application.dto.ProductRequest;
import com.acme.backendfreshsense.inventory.application.service.ProductService;
import com.acme.backendfreshsense.monitoring.application.dto.RegisterDeviceRequest;
import com.acme.backendfreshsense.monitoring.application.service.DeviceService;
import com.acme.backendfreshsense.monitoring.application.service.SensorIngestion;
import com.acme.backendfreshsense.monitoring.infrastructure.persistence.jpa.SensorObservation.Source;
import com.acme.backendfreshsense.traceability.application.TraceabilityService;
import com.acme.backendfreshsense.traceability.domain.FoodLot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SensorIngestionIntegrationTest {
    private static final String FIRMWARE = "/api/v1/sensor-readings";
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountService accounts;
    @Autowired ProductService products;
    @Autowired DeviceService devices;
    @Autowired TraceabilityService traces;
    @Autowired SensorIngestion ingestion;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired EntityManager entities;
    @MockitoBean FirmwareIngressSettings settings;

    private final AtomicReference<List<FirmwareIngressSettings.Binding>> bindings = new AtomicReference<>(List.of());
    private Device sensor;
    private String readPrefix;

    private record Device(long owner, long key, String id, String token, String deviceKey) {}

    @BeforeEach
    void fixture() {
        readPrefix = UUID.randomUUID().toString();
        sensor = newDevice();
        bind(sensor, Source.SIMULATED, ZoneId.of("America/Lima"), "FS-FRUIT-1");
        when(settings.authenticate(anyString(), nullable(String.class))).thenAnswer(call ->
                new FirmwareIngressSettings(bindings.get()).authenticate(call.getArgument(0), call.getArgument(1)));
    }

    @Test
    void acceptsFirmwareShapeAndPreservesValuesWithoutInventingIdentityOrProvenance() throws Exception {
        bind(sensor, Source.DEVICE_UNVERIFIED, ZoneId.of("America/Lima"), "FS-FRUIT-1");
        var now = Instant.now().minusMillis(250);
        var raw = reading("raw", now.toString(), 13, sensor);
        assertThat(raw.path("source").asText()).isEqualTo("DEVICE_UNVERIFIED");
        assertThat(raw.path("timeQuality").asText()).isEqualTo("UNSYNCHRONIZED");
        assertThat(raw.path("measuredAt").asText()).startsWith(now.toString().substring(0, 19));
        assertThat(raw.path("reading").path("temperature").asDouble()).isEqualTo(13);
        assertThat(raw.path("reading").path("humidity").asDouble()).isEqualTo(92.25);
        assertThat(raw.path("reading").path("ethyleneLevel").asDouble()).isEqualTo(0.6);
        assertThat(raw.path("traceability").path("outcome").asText()).isEqualTo("UNTRUSTED_TIME");

        var payload = payload(sensor.id(), now.toString(), 13);
        JsonNode first = response(send(sensor.token(), null, null, payload), 201);
        JsonNode second = response(send(sensor.token(), null, null, payload), 201);
        assertThat(evidence(first)).isNull();
        assertThat(outcome(first)).isEqualTo("NO_STABLE_ID");
        assertThat(first.at("/reading/id").asLong()).isNotEqualTo(second.at("/reading/id").asLong());
        send("invalid", "a", "SYNCED", payload).andExpect(status().isUnauthorized());
        send(sensor.deviceKey(), "a", "SYNCED", payload).andExpect(status().isUnauthorized());
        send(sensor.token(), "a", "SYNCED", payload(newDevice().id(), now.toString(), 13))
                .andExpect(status().isUnauthorized());
        send(sensor.token(), "a", "SYNCED", payload(sensor.id(), now.toString(), 81)).andExpect(status().isBadRequest());
        send(sensor.token(), "a", "UNRECOGNIZED", payload).andExpect(status().isBadRequest());
    }

    @Test
    void serializesConcurrentRetransmissionsAndRejectsDifferentContent() throws Exception {
        var lot = lot(sensor, "Frutas");
        var assigned = traces.assign(sensor.owner(), lot.id(), sensor.id());
        var payload = payload(sensor.id(), assigned.startedAt().toString(), 13);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var barrier = new CyclicBarrier(2);
            Callable<org.springframework.mock.web.MockHttpServletResponse> submit = () -> {
                barrier.await(6, TimeUnit.SECONDS);
                return send(sensor.token(), readPrefix, "SYNCED", payload).andReturn().getResponse();
            };
            var left = pool.submit(submit);
            var right = pool.submit(submit);
            var a = left.get(15, TimeUnit.SECONDS);
            var b = right.get(15, TimeUnit.SECONDS);
            assertThat(List.of(a.getStatus(), b.getStatus())).containsExactlyInAnyOrder(200, 201);
            JsonNode first = json.readTree(a.getContentAsString());
            JsonNode second = json.readTree(b.getContentAsString());
            assertThat(first.at("/reading/id").asLong()).isEqualTo(second.at("/reading/id").asLong());
            assertThat(first.at("/receivedAt").asText()).isEqualTo(second.at("/receivedAt").asText());
            UUID id = evidence(first);
            assertThat(id).isNotNull().isEqualTo(evidence(second));
            assertThat(traces.verify(sensor.owner(), id)).isTrue();
            assertThat(count("trace_evidence_outbox", "evidence_id = UUID_TO_BIN(?)", id.toString())).isEqualTo(1);
            assertThat(count("monitoring_readings", "external_id = ?", readPrefix)).isEqualTo(1);
            send(sensor.token(), readPrefix, "SYNCED", payload(sensor.id(), assigned.startedAt().toString(), 14))
                    .andExpect(status().isConflict());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void usesProfileLimitsAndHistoricalAssignmentOnBothSidesOfItsBoundaries() throws Exception {
        var lot = lot(sensor, "Frutas");
        var association = traces.assign(sensor.owner(), lot.id(), sensor.id());
        var closed = traces.close(sensor.owner(), lot.id(), association.id());
        assertThat(outcome(reading("before", closed.startedAt().minusNanos(1000).toString(), 13, sensor))).isEqualTo("NO_ASSIGNMENT");
        assertThat(outcome(reading("after", closed.endedAt().toString(), 13, sensor))).isEqualTo("NO_ASSIGNMENT");
        for (double temperature : List.of(4.0, 10.0, -0.5, -1.0, 12.0)) {
            var regular = reading("regular-" + temperature, closed.startedAt().toString(), temperature, sensor);
            assertThat(outcome(regular)).isEqualTo("NON_CRITICAL");
        }
        var high = critical(reading("high", closed.startedAt().toString(), 12.1, sensor));
        var low = critical(reading("low", closed.startedAt().toString(), -1.1, sensor));
        assertThat(high.path("assignmentId").asText()).isEqualTo(closed.id().toString());
        assertThat(high.path("thresholdC").asText()).isEqualTo("12");
        assertThat(low.path("thresholdC").asText()).isEqualTo("-1");
        assertThat(low.path("direction").asText()).isEqualTo("BELOW");
        assertThat(high.path("source").asText()).isEqualTo("SIMULATED");

        var otherLot = lot(sensor, "Lácteos");
        var otherAssignment = traces.assign(sensor.owner(), otherLot.id(), sensor.id());
        assertThat(outcome(reading("not-fruit", otherAssignment.startedAt().toString(), 14, sensor)))
                .isEqualTo("PROFILE_NOT_APPLICABLE");
    }

    @Test
    void qualifiesTimestampsAndDistinguishesGatewayFromClientDeclaredSimulation() throws Exception {
        var lot = lot(sensor, "Frutas");
        var association = traces.assign(sensor.owner(), lot.id(), sensor.id());
        bind(sensor, Source.AUTHENTICATED_GATEWAY, null, "FS-FRUIT-1");
        assertThat(critical(reading("gateway", association.startedAt().toString(), 13, sensor)).path("source").asText())
                .isEqualTo("AUTHENTICATED_GATEWAY");
        var cases = Map.of("INVALID", "not-a-time", "NO_ZONE", "2026-11-15T12:00:00",
                "STALE", Instant.now().minus(Duration.ofHours(25)).toString(), "FUTURE", Instant.now().plusSeconds(90).toString());
        for (var entry : cases.entrySet()) {
            var result = reading(entry.getKey(), entry.getValue(), 13, sensor);
            assertThat(result.path("timeQuality").asText()).isEqualTo(entry.getKey());
            assertThat(evidence(result)).isNull();
        }
        var spoofed = payload(sensor.id(), association.startedAt().toString(), 13);
        spoofed.put("meta", "origen=supuesto");
        var result = response(send(sensor.token(), readPrefix + "-sim", "SYNCED", spoofed), 201);
        assertThat(result.path("source").asText()).isEqualTo("UNVERIFIED_SIMULATION");
        assertThat(evidence(result)).isNull();
        var withoutClock = response(send(sensor.token(), readPrefix + "-no-clock", null,
                payload(sensor.id(), association.startedAt().toString(), 13)), 201);
        assertThat(withoutClock.path("timeQuality").asText()).isEqualTo("UNSYNCHRONIZED");
    }

    @Test
    void preservesLegacyResponsesAndDeduplicatesLegacyEdgeIdentifiers() throws Exception {
        var reading = Map.of("deviceId", sensor.id(), "id", readPrefix, "temperature", 13, "humidity", 90,
                "ethylenePpm", 0.6, "status", "RED", "category", "Frutas", "time", "bad timestamp");
        var original = response(legacy(reading), 201);
        var repeated = response(legacy(reading), 200);
        assertThat(original.path("status").asText()).isEqualTo("RED");
        assertThat(repeated.path("id").asLong()).isEqualTo(original.path("id").asLong());
        assertThat(count("monitoring_sensor_observations", "reading_id = ? AND time_quality = 'INVALID'",
                original.path("id").asLong())).isEqualTo(1);
        assertThat(count("trace_evidences", "sensor_reading_id = ?", original.path("id").asLong())).isZero();
    }

    @Test
    void rollsBackReadingEvidenceAndOutboxOnARealMysqlConstraintFailure() {
        var lot = lot(sensor, "Frutas");
        var association = traces.assign(sensor.owner(), lot.id(), sensor.id());
        var result = new AtomicReference<SensorIngestion.Result>();
        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            result.set(ingestion.ingest(sensor.owner(), sensor.key(), sensor.id(), new SensorIngestion.Sample(
                    readPrefix, association.startedAt().toString(), 13.0, 92.0, 0.6, null, null, null,
                    null, "SYNCED", Source.SIMULATED, "FS-FRUIT-1")));
            entities.flush();
            assertThat(result.get().traceability().evidenceId()).isNotNull();
            jdbc.update("""
                    INSERT INTO trace_evidence_outbox (evidence_id, version, status, attempts)
                    SELECT evidence_id, version, status, attempts FROM trace_evidence_outbox
                    WHERE evidence_id = UUID_TO_BIN(?)
                    """, result.get().traceability().evidenceId().toString());
        })).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(count("monitoring_readings", "id = ?", result.get().reading().id())).isZero();
        assertThat(count("monitoring_sensor_observations", "reading_id = ?", result.get().reading().id())).isZero();
        String id = result.get().traceability().evidenceId().toString();
        assertThat(count("trace_evidences", "id = UUID_TO_BIN(?)", id)).isZero();
        assertThat(count("trace_evidence_outbox", "evidence_id = UUID_TO_BIN(?)", id)).isZero();
    }

    private int count(String table, String where, Object value) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class, value);
    }

    private ResultActions legacy(Map<String, ?> body) throws Exception {
        return mvc.perform(post("/api/edge/readings").header("X-Device-Key", sensor.deviceKey())
                .contentType(APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }

    private ResultActions send(String token, String id, String clock, Map<String, Object> body) throws Exception {
        var request = post(FIRMWARE).header("X-Device-Token", token)
                .contentType(APPLICATION_JSON).content(json.writeValueAsBytes(body));
        if (id != null) request.header("Idempotency-Key", id);
        if (clock != null) request.header("X-Measurement-Clock", clock);
        return mvc.perform(request);
    }

    private static Map<String, Object> payload(String deviceId, String time, double temperature) {
        return new LinkedHashMap<>(Map.of("deviceId", deviceId, "timestamp", time, "temperatureC", temperature,
                "humidityPct", 92.25, "ethylenePpm", 0.6, "meta", "origen=sensores"));
    }

    private JsonNode reading(String id, String time, double temperature, Device device) throws Exception {
        return response(send(device.token(), readPrefix + "-" + id, "SYNCED", payload(device.id(), time, temperature)), 201);
    }

    private JsonNode response(ResultActions action, int code) throws Exception {
        return json.readTree(action.andExpect(status().is(code)).andReturn().getResponse().getContentAsString());
    }

    private static String outcome(JsonNode result) {
        return result.at("/traceability/outcome").asText();
    }

    private static UUID evidence(JsonNode result) {
        var value = result.at("/traceability/evidenceId");
        return value.isTextual() ? UUID.fromString(value.asText()) : null;
    }

    private JsonNode critical(JsonNode result) throws Exception {
        assertThat(outcome(result)).isEqualTo("EVIDENCE_CREATED");
        UUID id = evidence(result);
        assertThat(id).isNotNull();
        assertThat(traces.verify(sensor.owner(), id)).isTrue();
        return json.readTree(traces.evidence(sensor.owner(), id).canonicalSnapshot());
    }

    private void bind(Device device, Source source, ZoneId zone, String profile) {
        bindings.set(List.of(new FirmwareIngressSettings.Binding(device.id(), device.key(), device.token(), source, zone, profile)));
    }

    private Device newDevice() {
        var user = accounts.register(new UserRegistrationRequest("sensor-" + UUID.randomUUID() + "@example.test",
                "Lab9-" + UUID.randomUUID(), "Laboratorio Sensor"));
        var device = devices.register(user.getId(), new RegisterDeviceRequest("sensor-" + UUID.randomUUID(), "Sensor de prueba"));
        var claim = devices.claim(device.pairingCode());
        return new Device(user.getId(), devices.listByUser(user.getId()).get(0).id(), device.deviceId(),
                UUID.randomUUID().toString(), claim.secretKey());
    }

    private FoodLot lot(Device device, String category) {
        var product = products.create(device.owner(), new ProductRequest("Producto de ensayo", null, category,
                9, null, LocalDate.now().plusDays(5)));
        return traces.register(device.owner(), product.id(), "SENSOR-" + UUID.randomUUID(), BigDecimal.ONE,
                FoodLot.Unit.KG, Instant.now(), null);
    }
}
