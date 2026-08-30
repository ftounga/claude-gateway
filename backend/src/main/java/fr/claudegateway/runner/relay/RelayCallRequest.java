package fr.claudegateway.runner.relay;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Enveloppe d'un appel d'outil relayé d'un pod à l'autre (F-38 / SF-38-12, contrat du relais §3).
 *
 * <p>Elle ne porte <b>aucune identité d'utilisateur</b> : l'appartenance du workspace a déjà été
 * vérifiée par le pod appelant ({@code requireOwned}), et un {@code userId} transporté ici
 * n'authentifierait rien. Le pod destinataire ne fait confiance qu'au secret partagé, puis route sur
 * sa propre socket locale — l'identité des trames runner continue de venir de la session, jamais du
 * message.</p>
 *
 * @param workspaceId workspace ciblé
 * @param callId      identifiant {@code tool_use} du fournisseur, recopié verbatim
 * @param tool        nom d'outil, exactement celui exposé au modèle
 * @param input       arguments, recopiés verbatim ; {@code null} ou non-objet vaut {@code {}}
 * @param timeoutMs   délai armé côté runner
 */
public record RelayCallRequest(UUID workspaceId, String callId, String tool, JsonNode input,
        long timeoutMs) {

    /** Vrai si l'enveloppe est exploitable telle quelle (le reste est validé par le dispatcher). */
    boolean isValid() {
        return workspaceId != null && callId != null && !callId.isBlank() && tool != null
                && !tool.isBlank() && timeoutMs > 0;
    }
}
