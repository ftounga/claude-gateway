package fr.claudegateway.git.dto;

import java.time.OffsetDateTime;

/**
 * État du jeton GitHub de l'utilisateur. Ne contient <b>jamais</b> le jeton en clair : uniquement
 * une version masquée ({@code …last4}), le compte GitHub associé et des dates.
 *
 * @param present     vrai si un jeton est enregistré
 * @param githubLogin compte GitHub auquel le jeton donne accès (null si absent ou inconnu)
 * @param maskedToken version masquée {@code …last4} (null si absent)
 * @param last4       4 derniers caractères du jeton (null si absent)
 * @param createdAt   date du premier enregistrement (null si absent)
 * @param updatedAt   date de la dernière vérification réussie (null si absent)
 */
public record GitTokenStatusResponse(
        boolean present,
        String githubLogin,
        String maskedToken,
        String last4,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /** État « aucun jeton enregistré ». */
    public static GitTokenStatusResponse absent() {
        return new GitTokenStatusResponse(false, null, null, null, null, null);
    }
}
