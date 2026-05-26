package com.open.spring.mvc.email;

import java.util.List;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

@Data
public class SendEmailRequest {

    @NotEmpty
    private List<@NotBlank @Email String> to;

    @NotBlank
    private String subject;

    @NotBlank
    private String body;
}
