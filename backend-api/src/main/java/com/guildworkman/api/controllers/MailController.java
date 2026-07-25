package com.guildworkman.api.controllers;

import com.guildworkman.api.dto.requests.RegistrationRequest;
import com.guildworkman.api.dto.requests.SendMailRequest;
import com.guildworkman.api.dto.responses.ApiResponse;
import com.guildworkman.api.services.ServiceUtils.MailService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import static org.springframework.http.HttpStatus.CREATED;

@AllArgsConstructor
@RestController
@RequestMapping("/api/v1/mail")
public class MailController {
    private final MailService mailService;

    @PostMapping("/sendMail")
    public ResponseEntity<?>sendMail(@Valid @RequestBody SendMailRequest sendMailRequest) {
        return ResponseEntity.status(CREATED)
                .body(new ApiResponse(mailService.sendMail(sendMailRequest),true));

    }
}
