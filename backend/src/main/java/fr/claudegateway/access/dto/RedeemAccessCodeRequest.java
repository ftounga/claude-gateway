package fr.claudegateway.access.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Saisie d'un code d'accès (F-62 / SF-62-01). La normalisation (espaces, casse) est faite par le
 * serveur : la casse d'une saisie ne porte aucune information, et la laisser décider d'un refus
 * ferait échouer un code parfaitement correct.
 *
 * @param code code saisi par l'utilisateur
 */
public record RedeemAccessCodeRequest(
        @NotBlank(message = "Le code est obligatoire.")
        @Size(max = 32, message = "Le code ne peut pas dépasser 32 caractères.")
        String code) {
}
