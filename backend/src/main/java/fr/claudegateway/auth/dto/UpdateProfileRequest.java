package fr.claudegateway.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de {@code PUT /api/me} : mise à jour du profil. V1 = seul l'e-mail est éditable
 * (changer l'e-mail repasse le compte en « non vérifié » et renvoie un e-mail de vérification).
 */
public record UpdateProfileRequest(
        @NotBlank @Email @Size(max = 320) String email) {
}
