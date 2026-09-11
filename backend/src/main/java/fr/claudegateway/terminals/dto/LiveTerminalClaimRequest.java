package fr.claudegateway.terminals.dto;

import fr.claudegateway.terminals.LiveTerminal;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Prise ou renouvellement d'une place de terminal vivant (F-70 / SF-70-01).
 *
 * <p>Le {@code sessionId} identifie un <b>onglet</b>, pas un utilisateur : il est toujours lu avec
 * le {@code user_id} du jeton, ne donne accès à rien par lui-même, et n'est jamais journalisé. Son
 * alphabet est volontairement étroit — il finit dans des requêtes et des journaux, et rien n'exige
 * qu'il accepte autre chose qu'un UUID.</p>
 */
public record LiveTerminalClaimRequest(
        @NotBlank(message = "L'identifiant d'onglet est obligatoire")
        @Size(max = LiveTerminal.MAX_SESSION_ID_LENGTH,
                message = "L'identifiant d'onglet est trop long")
        @Pattern(regexp = "[A-Za-z0-9_-]+",
                message = "L'identifiant d'onglet contient un caractère non autorisé")
        String sessionId) {
}
