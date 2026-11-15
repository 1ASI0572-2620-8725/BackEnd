package com.acme.backendfreshsense.traceability.infrastructure.web;

import com.acme.backendfreshsense.shared.infrastructure.exceptions.ErrorResponse;
import com.acme.backendfreshsense.shared.infrastructure.security.CurrentUser;
import com.acme.backendfreshsense.traceability.application.TraceabilityService;
import com.acme.backendfreshsense.traceability.domain.FoodLot;
import com.acme.backendfreshsense.traceability.domain.LotDeviceAssignment;
import com.acme.backendfreshsense.traceability.domain.TraceEvidence;
import com.acme.backendfreshsense.traceability.domain.TraceabilityConflictException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/traceability")
public class TraceabilityController {
    private static final String LOCAL_STATUS = "LOCAL_PENDING_BLOCKCHAIN";
    private final TraceabilityService traceability;

    public TraceabilityController(TraceabilityService traceability) {
        this.traceability = traceability;
    }

    public record RegisterLotRequest(@NotBlank @Size(max = 64) String code,
                                     @NotNull @Positive Long productId,
                                     @NotNull @Positive BigDecimal initialQuantity,
                                     @NotNull FoodLot.Unit unit,
                                     @NotNull Instant receivedAt,
                                     LocalDate expiresOn) {}

    public record AssignDeviceRequest(@NotBlank @Size(max = 100) String deviceId) {}

    public record AssignmentResponse(UUID id, UUID lotId, String deviceId, Instant startedAt, Instant endedAt,
                                     LotDeviceAssignment.Status status) {
        static AssignmentResponse from(LotDeviceAssignment value) {
            return new AssignmentResponse(value.id(), value.lotId(), value.deviceId(),
                    value.startedAt(), value.endedAt(), value.status());
        }
    }

    public record EvidenceResponse(UUID id, UUID lotId, UUID businessEventId, TraceEvidence.Type type,
                                   int schemaVersion, String canonicalSnapshot, String sha256,
                                   Instant createdAt, TraceEvidence.Source source, String status) {
        static EvidenceResponse from(TraceEvidence value) {
            return new EvidenceResponse(value.id(), value.lotId(), value.businessEventId(), value.type(),
                    value.schemaVersion(), value.canonicalSnapshot(), value.sha256(), value.createdAt(),
                    value.source(), LOCAL_STATUS);
        }
    }

    public record VerificationResponse(UUID evidenceId, boolean matches, String status) {}

    @ExceptionHandler(TraceabilityConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse conflict(TraceabilityConflictException error) {
        return new ErrorResponse(409, error.getMessage());
    }

    @PostMapping("/lots")
    public ResponseEntity<FoodLot> register(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody RegisterLotRequest request) {
        var lot = traceability.register(writeUser(authorization), request.productId(), request.code(),
                request.initialQuantity(), request.unit(), request.receivedAt(), request.expiresOn());
        return ResponseEntity.created(URI.create("/api/traceability/lots/" + lot.id())).body(lot);
    }

    @GetMapping("/lots")
    public List<FoodLot> lots() {
        return traceability.lots(CurrentUser.id());
    }

    @GetMapping("/lots/{lotId}")
    public FoodLot lot(@PathVariable String lotId) {
        return traceability.lot(CurrentUser.id(), UUID.fromString(lotId));
    }

    @PostMapping("/lots/{lotId}/assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public AssignmentResponse assign(@PathVariable String lotId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @Valid @RequestBody AssignDeviceRequest request) {
        return AssignmentResponse.from(traceability.assign(writeUser(authorization), UUID.fromString(lotId),
                request.deviceId()));
    }

    @PostMapping("/lots/{lotId}/assignments/{assignmentId}/close")
    public AssignmentResponse close(@PathVariable String lotId, @PathVariable String assignmentId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return AssignmentResponse.from(traceability.close(writeUser(authorization), UUID.fromString(lotId),
                UUID.fromString(assignmentId)));
    }

    @GetMapping("/lots/{lotId}/assignments")
    public List<AssignmentResponse> assignments(@PathVariable String lotId) {
        return traceability.assignments(CurrentUser.id(), UUID.fromString(lotId)).stream()
                .map(AssignmentResponse::from).toList();
    }

    @GetMapping("/lots/{lotId}/evidences")
    public List<EvidenceResponse> evidences(@PathVariable String lotId) {
        return traceability.evidences(CurrentUser.id(), UUID.fromString(lotId)).stream()
                .map(EvidenceResponse::from).toList();
    }

    @GetMapping("/evidences/{evidenceId}")
    public EvidenceResponse evidence(@PathVariable String evidenceId) {
        return EvidenceResponse.from(traceability.evidence(CurrentUser.id(), UUID.fromString(evidenceId)));
    }

    @GetMapping("/evidences/{evidenceId}/verify")
    public VerificationResponse verify(@PathVariable String evidenceId) {
        UUID id = UUID.fromString(evidenceId);
        return new VerificationResponse(id, traceability.verify(CurrentUser.id(), id), LOCAL_STATUS);
    }

    private static long writeUser(String authorization) {
        // Selecciona el mismo tipo de credencial que JwtAuthFilter prioriza. Una cookie sola no autoriza escrituras.
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() == 7) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Las escrituras de trazabilidad requieren JWT Bearer");
        }
        return CurrentUser.id();
    }
}
