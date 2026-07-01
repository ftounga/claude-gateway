package fr.claudegateway.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Corps de la requête d'inscription email / mot de passe.
 *
 * <p>Le mot de passe est plafonné à 72 caractères : au-delà, BCrypt ignore silencieusement
 * les octets excédentaires ; on valide donc la borne en amont pour un comportement prévisible.</p>
 */
public record RegisterRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 8, max = 72) String password) {
}
