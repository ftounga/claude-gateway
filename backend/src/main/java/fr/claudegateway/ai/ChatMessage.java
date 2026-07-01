package fr.claudegateway.ai;

/**
 * Message d'un échange transmis au fournisseur IA. Structure neutre (rôle + contenu),
 * sans détail spécifique à un fournisseur.
 *
 * @param role    rôle de l'auteur du message
 * @param content contenu textuel du message
 */
public record ChatMessage(ChatRole role, String content) {
}
