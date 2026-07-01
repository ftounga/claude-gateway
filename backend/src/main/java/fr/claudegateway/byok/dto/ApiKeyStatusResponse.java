package fr.claudegateway.byok.dto;

import java.time.OffsetDateTime;

/**
 * Statut de la clé BYOK de l'utilisateur. Ne contient <b>jamais</b> la clé en clair : uniquement une
 * version masquée ({@code sk-…last4}) et des métadonnées.
 *
 * @param present     vrai si une clé est enregistrée
 * @param maskedKey   version masquée {@code sk-…last4} (null si absente)
 * @param last4       4 derniers caractères de la clé (null si absente)
 * @param provider    fournisseur associé (ex. {@code ANTHROPIC}), null si absente
 * @param mode        mode fournisseur effectif : {@code BYOK} si une clé active existe, sinon {@code HOSTED}
 * @param validatedAt date de dernière validation réussie (null si absente)
 * @param createdAt   date d'enregistrement (null si absente)
 */
public record ApiKeyStatusResponse(
        boolean present,
        String maskedKey,
        String last4,
        String provider,
        String mode,
        OffsetDateTime validatedAt,
        OffsetDateTime createdAt) {

    /** Statut « aucune clé » : mode Hosted par défaut. */
    public static ApiKeyStatusResponse absent() {
        return new ApiKeyStatusResponse(false, null, null, null, "HOSTED", null, null);
    }
}
