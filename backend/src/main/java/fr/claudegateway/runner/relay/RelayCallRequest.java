package fr.claudegateway.runner.relay;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

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
 *
 * <p><b>Tolérant aux champs inconnus (F-81 / SF-81-02).</b> Cette enveloppe est écrite par un
 * <b>autre pod</b> de la gateway. Pendant une bascule progressive, deux versions cohabitent : le
 * pod qui émet peut être plus récent que celui qui lit. Un champ ajouté ne doit pas transformer
 * un relais d'appel en erreur — ce serait un tour d'atelier perdu, sur un poste qui travaille.</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RelayCallRequest(UUID hostId, UUID workspaceId, String project, String callId,
        String tool, JsonNode input, long timeoutMs) {

    /**
     * Vrai si l'enveloppe est exploitable telle quelle (le reste est validé par le dispatcher).
     *
     * <p>{@code workspaceId} peut être <b>nul</b> depuis F-71 / SF-71-02 : un appel de <b>poste</b>
     * — lister les sous-dossiers d'une racine pour désigner un projet qui n'existe pas encore — ne
     * concerne aucun projet. Ce champ n'autorise rien : l'appartenance a déjà été vérifiée par le
     * pod appelant, et le pod destinataire ne fait confiance qu'au secret partagé (contrat du relais
     * §3). Il ne sert qu'à l'isolation des <b>annulations</b> en vol ; nul, l'appel n'est simplement
     * pas annulable par projet — ce qui est exact, il n'en a pas.</p>
     */
    boolean isValid() {
        return hostId != null && callId != null && !callId.isBlank()
                && tool != null && !tool.isBlank() && timeoutMs > 0;
    }

    /** Cible reconstruite pour le dispatcher local du pod destinataire. */
    RunnerTarget target() {
        return new RunnerTarget(hostId, workspaceId, project);
    }
}
