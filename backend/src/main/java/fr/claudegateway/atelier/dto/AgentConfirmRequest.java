package fr.claudegateway.atelier.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Réponse de l'utilisateur à une demande d'autorisation de l'agent (F-33 / SF-33-02).
 *
 * @param toolUseId identifiant de la demande, tel que relayé par l'événement {@code confirm_request}
 *                  du flux. <b>Obligatoire</b> : il désigne l'action à trancher, et n'a d'effet que
 *                  dans la session du workspace visé
 * @param decision  {@code allow} ou {@code deny}. <b>Obligatoire</b> : sur un réglage de sécurité, on
 *                  ne devine pas l'intention — et un corps vide ne vaut pas autorisation
 * @param reason    motif du refus, relayé tel quel à l'agent pour qu'il propose autre chose.
 *                  Facultatif, borné à 500 caractères (une phrase, pas un rapport)
 */
public record AgentConfirmRequest(
        @NotBlank String toolUseId,
        @NotBlank @Pattern(regexp = "(?i)allow|deny") String decision,
        @Size(max = 500) String reason,
        Boolean allowAll) {

    /**
     * Vrai si l'utilisateur autorise <b>toutes</b> les commandes de ce message (F-38 / SF-38-20).
     *
     * <p>La portée est le <b>tour</b>, jamais le projet : le message suivant redemandera. C'est ce
     * qui distingue un raccourci d'un renoncement — on autorise ce qu'on a commencé à voir, pas
     * tout ce qui viendra un jour.</p>
     */
    public boolean allowsAll() {
        return Boolean.TRUE.equals(allowAll) && allows();
    }

    /** Vrai si la décision autorise l'exécution. */
    public boolean allows() {
        return "allow".equalsIgnoreCase(decision == null ? "" : decision.trim());
    }
}
