package fr.claudegateway.ai;

import java.util.List;

/**
 * Message d'un échange transmis au fournisseur IA. Structure neutre (rôle + contenu + pièces
 * jointes propres au message), sans détail spécifique à un fournisseur.
 *
 * <p>Les pièces jointes sont portées <b>par message</b> (F-25) : chaque message rejoué dans
 * l'historique ré-embarque ses propres fichiers, ce qui permet à une pièce jointe de rester dans le
 * contexte de toute la conversation (comme claude.ai), et pas seulement au tour où elle a été jointe.</p>
 *
 * @param role        rôle de l'auteur du message
 * @param content     contenu textuel du message
 * @param attachments fichiers rattachés à ce message (jamais {@code null} ; vide par défaut)
 */
public record ChatMessage(ChatRole role, String content, List<ProviderAttachment> attachments) {

    public ChatMessage {
        if (attachments == null) {
            attachments = List.of();
        }
    }

    /** Message sans pièce jointe. */
    public ChatMessage(ChatRole role, String content) {
        this(role, content, List.of());
    }
}
