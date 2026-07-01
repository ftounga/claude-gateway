package fr.claudegateway.auth.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Corps de la requête de connexion email / mot de passe.
 * La longueur n'est pas contrainte ici : une valeur erronée aboutit à {@code 401} générique.
 */
public record LoginRequest(
        @NotBlank String email,
        @NotBlank String password) {
}
