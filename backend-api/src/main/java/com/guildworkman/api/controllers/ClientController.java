package com.guildworkman.api.controllers;

import com.guildworkman.api.data.models.ConsultationAvailability;
import com.guildworkman.api.dto.requests.*;
import com.guildworkman.api.dto.responses.ApiResponse;
import com.guildworkman.api.dto.responses.ConsultationResponse;
import com.guildworkman.api.services.ServiceUtils.ClientService;
import com.guildworkman.api.services.ServiceUtils.ConsultationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.springframework.http.HttpStatus.CREATED;

@RestController
@RequestMapping("/api/v1/client")
@AllArgsConstructor
@Validated
public class ClientController {

    private static final Logger log = LoggerFactory.getLogger(ClientController.class);
    private final ClientService clientService;
    private final ConsultationService consultationService;


    @PostMapping("/bookAppointment")
    public ResponseEntity<?>bookAppointment(@Valid @RequestBody BookAppointmentRequest bookAppointmentRequest){
        return ResponseEntity.status(CREATED)
                .body(new ApiResponse
                        (clientService.bookAppointment(bookAppointmentRequest),true));
    }

    @PostMapping("/registerClient")
    public ResponseEntity<?> registerClient(@Valid @RequestBody RegistrationRequest registrationRequest){
//        log.info("Registering Client => {}", registrationRequest.toString());
        return ResponseEntity.status(CREATED)
               .body(new ApiResponse
                        (clientService.registerClient(registrationRequest),true));
    }
    // These change existing state, so they return 200 OK, not 201 CREATED, and
    // take only the appointment id. The cancel endpoint used to declare a
    // CancelAppointmentRequest with no @RequestBody, so Spring bound it from the
    // query string — meaning a cancel silently required an extra, undocumented
    // `id` param (the *client* id) and failed with "The given id must not be
    // null" for every caller that sent just appointmentId.
    @PutMapping("/cancelAppointment")
    public ResponseEntity<?> cancelAppointment(@RequestParam @NotNull Long appointmentId){
        return ResponseEntity.status(HttpStatus.OK)
               .body(new ApiResponse
                        (clientService.cancelAppointment(appointmentId),true));
    }

    @PutMapping("/updateAppointment")
    public ResponseEntity<?> updateAppointment(@RequestParam @NotNull Long appointmentId, @Valid @RequestBody UpdateAppointmentRequest request){
        return ResponseEntity.status(HttpStatus.OK)
               .body(new ApiResponse
                        (clientService.updateAppointment(appointmentId, request),true));
    }
    @DeleteMapping("/deleteAppointment")
    public ResponseEntity<?> deleteAppointment(@RequestParam @NotNull Long appointmentId){
        return ResponseEntity.status(HttpStatus.OK)
               .body(new ApiResponse
                        (clientService.deleteAppointment(appointmentId),true));
    }

    @GetMapping("/viewAllAppointment")
    public ResponseEntity<?> viewAllAppointment(@RequestParam @NotNull Long clientId){
        // A read returns 200 OK, not 201 CREATED.
        return ResponseEntity.status(HttpStatus.OK)
               .body(new ApiResponse
                        (clientService.viewAllAppointment(clientId),true));
    }

    @PutMapping("/updateClientProfile")
    public ResponseEntity<?> updateClientProfile(@Valid @RequestBody UpdateClientRequest request) {
        return ResponseEntity
                .ok(new ApiResponse(clientService.updateClientProfile(request), true));
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest loginRequest){
        return ResponseEntity.status(CREATED)
                .body(new ApiResponse(clientService.login(loginRequest),true));
    }

    @RequestMapping(value = "/api/**", method = RequestMethod.OPTIONS)
    public ResponseEntity<?> handleOptions() {
        return ResponseEntity.ok().build();
    }
    @PostMapping("/consult")
    public ResponseEntity<ConsultationResponse> bookConsultation(
            @RequestParam @NotNull Long clientId,
            @RequestParam @NotNull Long workerId,
            @RequestParam @NotBlank String details) {
        ConsultationResponse response = consultationService.bookConsultation(clientId, workerId, details);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }
    @PostMapping("/{consultationId}/availability")
    public ResponseEntity<ConsultationAvailability> scheduleAvailability(
            @PathVariable @NotNull Long consultationId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime clientAvailability,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime workerAvailability) {
        ConsultationAvailability availability = consultationService.scheduleAvailability(
                consultationId, clientAvailability, workerAvailability);
        return new ResponseEntity<>(availability, HttpStatus.CREATED);
    }

}
