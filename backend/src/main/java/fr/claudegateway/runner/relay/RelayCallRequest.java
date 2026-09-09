package fr.claudegateway.runner.relay;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.runner.channel.RunnerTarget;

/**
 * Enveloppe d'un appel d'outil relayé d'un pod à l'autre (F-38 / SF-38-12, contrat du relais §3).
 *
 * <p>Elle ne porte <b>aucune identité d'utilisateur</b> : l'appartenance du poste et du projet a
 * déjà été vérifiée par le pod appelant, et un {@code userId} transporté ici n'authentifierait rien.
 * Le pod destinataire ne fait confiance qu'au secret partagé, puis route sur sa propre socket
 * locale — l'identité des trames runner continue de venir de la session, jamais du message.</p>
 *
 * <p>Depuis F-48 / SF-48-01, elle porte le <b>poste</b> (la clef du routage), le <b>projet</b> (pour
 * que l'annulation d'un tour ne touche pas les autres projets de la même machine) et son
 * <b>chemin relatif</b>, qui repart tel quel dans la trame {@code tool_call}.</p>
 *
 * @param hostId      poste qui exécute
 * @param workspaceId projet concerné, jamais transmis au runner
 * @param project     chemin relatif du projet sous la racine du poste ({@code ""} = la racine)
 * @param callId      identifiant {@code tool_use} du fournisseur, recopié verbatim
 * @param tool        nom d'outil, exactement celui exposé au modèle
 * @param input       arguments, recopiés verbatim ; {@code null} ou non-objet vaut {@code {}}
 * @param timeoutMs   délai armé côté runner
 */
public record RelayCallRequest(UUID hostId, UUID workspaceId, String project, String callId,
        String tool, JsonNode input, long timeoutMs) {

    /** Vrai si l'enveloppe est exploitable telle quelle (le reste est validé par le dispatcher). */
    boolean isValid() {
        return hostId != null && workspaceId != null && callId != null && !callId.isBlank()
                && tool != null && !tool.isBlank() && timeoutMs > 0;
    }

    /** Cible reconstruite pour le dispatcher local du pod destinataire. */
    RunnerTarget target() {
        return new RunnerTarget(hostId, workspaceId, project);
    }
}
