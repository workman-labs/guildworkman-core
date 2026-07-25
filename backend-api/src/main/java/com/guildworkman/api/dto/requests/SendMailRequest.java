package com.guildworkman.api.dto.requests;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Setter
@Getter
@NoArgsConstructor
public class SendMailRequest {

    @NotBlank
    @Email
    private String recipientEmail;

    @NotBlank
    private String subject;

    @NotBlank
    private String recipientName;

    @NotBlank
    private String content;
}
