package com.acme.backendfreshsense.traceability;

import com.acme.backendfreshsense.accounts.application.dto.UserRegistrationRequest;
import com.acme.backendfreshsense.accounts.application.service.AccountService;
import com.acme.backendfreshsense.inventory.application.dto.ProductRequest;
import com.acme.backendfreshsense.inventory.application.service.ProductService;
import com.acme.backendfreshsense.monitoring.application.dto.RegisterDeviceRequest;
import com.acme.backendfreshsense.monitoring.application.service.DeviceService;
import com.acme.backendfreshsense.traceability.application.TraceabilityService;
import com.acme.backendfreshsense.traceability.domain.FoodLot;
import com.acme.backendfreshsense.traceability.domain.TraceabilityRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TraceabilityIntegrationTest {
    private static final String LOTS = "/api/traceability/lots";
    private static final String EVIDENCE = "/api/traceability/evidences/";
    private static final String RECEIVED_AT = "2026-11-15T06:10:00.123456-05:00";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired AccountService accounts;
    @Autowired ProductService inventory;
    @Autowired DeviceService devices;
    @Autowired TraceabilityService traceability;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean TraceabilityRepository records;

    private User owner;
    private User outsider;
    private long productId;

    private record User(long id, String token) {
        String bearer() { return "Bearer " + token; }
    }

    @BeforeEach
    void createFixture() {
        owner = newUser();
        outsider = newUser();
        productId = product(owner);
    }

    @Test
    void authenticatesRequestsAndScopesEveryReadToTheOwner() throws Exception {
        mvc.perform(get(LOTS)).andExpect(status().isUnauthorized());
        mvc.perform(post(LOTS).cookie(new Cookie("authToken", owner.token())).contentType(APPLICATION_JSON)
                .content(lotBody("CSRF", productId, "1"))).andExpect(status().isForbidden());
        var lot = register(" VISTA-1 ");
        var id = lot.path("id").asText();
        var evidenceId = lot.path("initialEvidenceId").asText();
        var assignment = response(assign(owner, id, device(owner)), 201);
        var assignmentId = assignment.path("id").asText();

        mvc.perform(get(LOTS).header(AUTHORIZATION, owner.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id));
        mvc.perform(get(LOTS).header(AUTHORIZATION, outsider.bearer()))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        for (String path : List.of(LOTS + "/" + id, LOTS + "/" + id + "/assignments",
                LOTS + "/" + id + "/evidences", EVIDENCE + evidenceId, EVIDENCE + evidenceId + "/verify")) {
            mvc.perform(get(path).header(AUTHORIZATION, outsider.bearer())).andExpect(status().isNotFound());
        }
        mvc.perform(get(LOTS + "/" + UUID.randomUUID()).header(AUTHORIZATION, owner.bearer()))
                .andExpect(status().isNotFound());
        mvc.perform(get(LOTS + "/malformed").header(AUTHORIZATION, owner.bearer()))
                .andExpect(status().isBadRequest());
        assign(outsider, id, device(outsider)).andExpect(status().isNotFound());
        close(outsider, id, assignmentId).andExpect(status().isNotFound());
    }

    @Test
    void validatesProductQuantityAndNormalizedUniquenessPerOwner() throws Exception {
        submit(owner, "MISSING", Long.MAX_VALUE, "1").andExpect(status().isNotFound());
        submit(owner, "FOREIGN", product(outsider), "1").andExpect(status().isNotFound());
        for (String invalid : List.of("0", "-1", "2.0001", "1000000000000")) {
            submit(owner, "INVALID", productId, invalid).andExpect(status().isBadRequest());
        }
        var first = response(submit(owner, " lot-1 ", productId, "2.5"), 201);
        assertThat(first.path("code").asText()).isEqualTo("LOT-1");
        assertThat(Instant.parse(first.path("receivedAt").asText())).isEqualTo(Instant.parse(RECEIVED_AT));

        submit(owner, "LOT-1", product(owner), "3").andExpect(status().isConflict());
        submit(owner, "LOT-2", productId, "3").andExpect(status().isCreated());
        submit(outsider, "LOT-1", product(outsider), "3").andExpect(status().isCreated());

        mvc.perform(get("/api/products").header(AUTHORIZATION, owner.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].quantity").value(9));
    }

    @Test
    void keepsHistoricalSnapshotAndProtectsReferencedProduct() throws Exception {
        var lot = register("HISTORY");
        String id = lot.path("id").asText();
        String evidenceId = lot.path("initialEvidenceId").asText();
        var initial = response(mvc.perform(get(EVIDENCE + evidenceId).header(AUTHORIZATION, owner.bearer())), 200);
        assertThat(initial.path("status").asText()).isEqualTo("LOCAL_PENDING_BLOCKCHAIN");
        assertThat(initial.path("lotId").asText()).isEqualTo(id);
        assertThat(initial.path("type").asText()).isEqualTo("LOT_REGISTERED");
        assertThat(initial.path("sha256").asText()).matches("[0-9a-f]{64}");
        String snapshot = initial.path("canonicalSnapshot").asText();
        assertThat(json.readTree(snapshot).path("productName").asText()).isEqualTo("Manzana original");

        mvc.perform(patch("/api/products/" + productId).header(AUTHORIZATION, owner.bearer())
                        .contentType(APPLICATION_JSON).content("{\"name\":\"Manzana nueva\"}"))
                .andExpect(status().isOk());
        mvc.perform(delete("/api/products/" + productId).header(AUTHORIZATION, owner.bearer()))
                .andExpect(status().isConflict());
        mvc.perform(get(LOTS + "/" + id + "/evidences").header(AUTHORIZATION, owner.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].canonicalSnapshot").value(snapshot));
        mvc.perform(get(EVIDENCE + evidenceId + "/verify").header(AUTHORIZATION, owner.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matches").value(true));

        jdbc.update("UPDATE trace_evidences SET canonical_snapshot = ? WHERE sha256 = ?",
                snapshot.replace("Manzana original", "Manzana alterada"), initial.path("sha256").asText());
        mvc.perform(get(EVIDENCE + evidenceId + "/verify").header(AUTHORIZATION, owner.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matches").value(false));
        jdbc.update("UPDATE trace_evidences SET canonical_snapshot = ? WHERE sha256 = ?",
                snapshot, initial.path("sha256").asText());
        jdbc.update("UPDATE trace_food_lots SET initial_quantity = 7 WHERE owner_id = ? AND code = 'HISTORY'", owner.id());
        mvc.perform(get(EVIDENCE + evidenceId + "/verify").header(AUTHORIZATION, owner.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matches").value(false));
    }

    @Test
    void closesIntervalsAndKeepsAssignmentHistory() throws Exception {
        String first = register("ASG-1").path("id").asText();
        String second = register("ASG-2").path("id").asText();
        String deviceId = device(owner);
        assign(owner, first, "not-found").andExpect(status().isNotFound());
        assign(owner, first, device(outsider)).andExpect(status().isNotFound());
        var active = response(assign(owner, first, deviceId), 201);
        String assignmentId = active.path("id").asText();
        assertThat(active.path("lotId").asText()).isEqualTo(first);
        assertThat(active.path("status").asText()).isEqualTo("ACTIVE");
        assign(owner, first, deviceId).andExpect(status().isConflict());
        assign(owner, second, deviceId).andExpect(status().isConflict());
        close(owner, second, assignmentId).andExpect(status().isNotFound());
        var closed = response(close(owner, first, assignmentId), 200);
        assertThat(closed.path("status").asText()).isEqualTo("CLOSED");
        Instant endedAt = Instant.parse(closed.path("endedAt").asText());
        assertThat(endedAt).isAfter(Instant.parse(closed.path("startedAt").asText()));
        close(owner, first, assignmentId).andExpect(status().isConflict());

        var reassigned = response(assign(owner, second, deviceId), 201);
        assertThat(Instant.parse(reassigned.path("startedAt").asText())).isAfterOrEqualTo(endedAt);
        mvc.perform(get(LOTS + "/" + first + "/assignments").header(AUTHORIZATION, owner.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(assignmentId))
                .andExpect(jsonPath("$[0].status").value("CLOSED"));
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM trace_lot_device_assignments WHERE owner_id = ? AND active_device_key IS NOT NULL
                """, Integer.class, owner.id())).isEqualTo(1);
    }

    @Test
    void serializesTwoSimultaneousAssignmentsForOneDevice() throws Exception {
        String first = register("PARALLEL-1").path("id").asText();
        String second = register("PARALLEL-2").path("id").asText();
        String deviceId = device(owner);
        var barrier = new CyclicBarrier(2);
        Callable<Integer> workerOne = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return assign(owner, first, deviceId).andReturn().getResponse().getStatus();
        };
        Callable<Integer> workerTwo = () -> {
            barrier.await(10, TimeUnit.SECONDS);
            return assign(owner, second, deviceId).andReturn().getResponse().getStatus();
        };
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(workerOne);
            var b = pool.submit(workerTwo);
            assertThat(List.of(a.get(15, TimeUnit.SECONDS), b.get(15, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(201, 409);
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM trace_lot_device_assignments WHERE owner_id = ? AND active_device_key IS NOT NULL
                    """, Integer.class, owner.id())).isEqualTo(1);
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO trace_lot_device_assignments
                      (id, lot_id, device_key, owner_id, active_device_key, started_at)
                    SELECT UNHEX(REPLACE(UUID(), '-', '')), lot_id, device_key, owner_id, active_device_key, started_at
                    FROM trace_lot_device_assignments WHERE owner_id = ? AND active_device_key IS NOT NULL
                    """, owner.id())).isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("uk_trace_assignment_active_device");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void rollsBackFlushedLotWhenInitialEvidenceCannotBeWritten() {
        doThrow(new DataIntegrityViolationException("Fallo simulado al guardar evidencia")).when(records).insertEvidence(any());
        assertThatThrownBy(() -> traceability.register(owner.id(), productId, "ROLLBACK", BigDecimal.ONE,
                FoodLot.Unit.KG, Instant.parse(RECEIVED_AT), null))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessage("Fallo simulado al guardar evidencia");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM trace_food_lots WHERE owner_id = ? AND code = ?",
                Integer.class, owner.id(), "ROLLBACK")).isZero();
    }

    @Test
    void storesOneInitialEvidenceAndEnforcesReferenceAndQuantityInMysql() throws Exception {
        register("PERSISTED");
        Integer linked = jdbc.queryForObject("""
                SELECT COUNT(*) FROM trace_food_lots l JOIN trace_evidences e
                  ON l.id = e.lot_id AND l.initial_evidence_id = e.id
                WHERE l.owner_id = ? AND l.code = ?
                """, Integer.class, owner.id(), "PERSISTED");
        assertThat(linked).isEqualTo(1);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE trace_food_lots SET product_id = ? WHERE owner_id = ? AND code = 'PERSISTED'
                """, Long.MAX_VALUE, owner.id())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE trace_food_lots SET initial_quantity = 0 WHERE owner_id = ? AND code = 'PERSISTED'
                """, owner.id())).hasRootCauseInstanceOf(java.sql.SQLException.class)
                .hasMessageContaining("ck_trace_lot_quantity");
    }

    private User newUser() {
        var user = accounts.register(new UserRegistrationRequest(
                "trace-" + UUID.randomUUID() + "@example.test", "Lab9-" + UUID.randomUUID(), "Prueba Trace"));
        return new User(user.getId(), user.getToken());
    }

    private long product(User user) {
        return inventory.create(user.id(),
                new ProductRequest("Manzana original", null, "Frutas", 9, null, LocalDate.parse("2026-12-01"))).id();
    }

    private String device(User user) {
        String id = "trace-" + UUID.randomUUID();
        devices.register(user.id(), new RegisterDeviceRequest(id, "Dispositivo de laboratorio"));
        return id;
    }

    private JsonNode register(String code) throws Exception {
        return response(submit(owner, code, productId, "2.500"), 201);
    }

    private String lotBody(String code, long productId, String quantity) throws Exception {
        return json.writeValueAsString(Map.of("code", code, "productId", productId,
                "initialQuantity", new BigDecimal(quantity), "unit", "KG", "receivedAt", RECEIVED_AT));
    }

    private ResultActions submit(User user, String code, long productId, String quantity) throws Exception {
        return mvc.perform(post(LOTS).header(AUTHORIZATION, user.bearer()).contentType(APPLICATION_JSON)
                .content(lotBody(code, productId, quantity)));
    }

    private ResultActions assign(User user, String lotId, String deviceId) throws Exception {
        return mvc.perform(post(LOTS + "/" + lotId + "/assignments").header(AUTHORIZATION, user.bearer())
                .contentType(APPLICATION_JSON).content(json.writeValueAsString(Map.of("deviceId", deviceId))));
    }

    private ResultActions close(User user, String lotId, String assignmentId) throws Exception {
        return mvc.perform(post(LOTS + "/" + lotId + "/assignments/" + assignmentId + "/close")
                .header(AUTHORIZATION, user.bearer()));
    }

    private JsonNode response(ResultActions action, int expected) throws Exception {
        return json.readTree(action.andExpect(status().is(expected)).andReturn().getResponse().getContentAsByteArray());
    }
}
