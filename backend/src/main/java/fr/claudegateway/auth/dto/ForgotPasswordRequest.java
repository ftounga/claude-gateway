package fr.claudegateway.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Corps de la demande de réinitialisation de mot de passe. */
public record ForgotPasswordRequest(
        @NotBlank @Email @Size(max = 320) String email) {
}
