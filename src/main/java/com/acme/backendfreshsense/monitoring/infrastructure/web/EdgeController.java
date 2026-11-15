package com.acme.backendfreshsense.monitoring.infrastructure.web;

import com.acme.backendfreshsense.monitoring.application.dto.ClaimRequest;
import com.acme.backendfreshsense.monitoring.application.dto.ClaimResponse;
import com.acme.backendfreshsense.monitoring.application.dto.EdgeReadingRequest;
import com.acme.backendfreshsense.monitoring.application.dto.MonitoringReadingDto;
import com.acme.backendfreshsense.monitoring.application.service.DeviceService;
import com.acme.backendfreshsense.monitoring.application.service.SensorIngestion;
import com.acme.backendfreshsense.monitoring.domain.model.Device;
import com.acme.backendfreshsense.monitoring.infrastructure.persistence.jpa.SensorObservation.Source;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/**
 * Punto de entrada para el Edge/IoT. El dispositivo físico (ESP32) envía aquí sus lecturas.
 * Flujo: IoT -> Edge -> este endpoint -> backend.
 *
 * <p>Autenticación: cabecera {@code X-Device-Key} con el secreto del dispositivo (no usa JWT).
 * El usuario dueño se resuelve a partir del dispositivo registrado.</p>
 */
@Tag(name = "Edge/IoT", description = "Ingesta de lecturas desde el Edge (dispositivos IoT)")
@RestController
@RequestMapping("/api/edge")
public class EdgeController {

    private final DeviceService deviceService;
    private final SensorIngestion ingress;

    public EdgeController(DeviceService deviceService, SensorIngestion ingress) {
        this.deviceService = deviceService;
        this.ingress = ingress;
    }

    @Operation(summary = "Recibir una lectura del Edge",
            description = "Recibe temperatura, humedad y etileno de un dispositivo IoT. Requiere cabecera X-Device-Key válida. " +
                          "El campo 'id' es el identificador de la lectura generado en el Edge; 'deviceId' identifica al sensor.")
    @PostMapping("/readings")
    public ResponseEntity<MonitoringReadingDto> ingest(
            @RequestHeader(value = "X-Device-Key", required = false) String deviceKey,
            @Valid @RequestBody EdgeReadingRequest request) {

        Device device = deviceService.authenticate(request.deviceId(), deviceKey);
        if (device == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Dispositivo no autorizado");
        }

        var result = ingress.ingest(device.getUserId(), device.getId(), device.getDeviceId(),
                new SensorIngestion.Sample(request.id(), request.time(), request.temperature(), request.humidity(),
                        request.ethylenePpm(), request.status(), request.category(), null, null, null,
                        Source.LEGACY_DEVICE, null));
        return ResponseEntity.status(result.duplicate() ? HttpStatus.OK : HttpStatus.CREATED).body(result.reading());
    }

    @Operation(summary = "Canjear un código de emparejamiento",
            description = "El Edge envía el código que el usuario obtuvo en la app y recibe la clave del dispositivo " +
                          "(X-Device-Key). El código es de un solo uso y temporal. Público: autenticado por el propio código.")
    @PostMapping("/claim")
    public ResponseEntity<ClaimResponse> claim(@Valid @RequestBody ClaimRequest request) {
        return ResponseEntity.ok(deviceService.claim(request.code()));
    }

}
