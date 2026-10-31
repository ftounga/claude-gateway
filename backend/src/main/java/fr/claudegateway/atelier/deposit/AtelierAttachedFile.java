package fr.claudegateway.atelier.deposit;

import java.util.UUID;

/**
 * Une pièce jointe telle qu'elle est rendue dans la bulle d'un message (F-169 / SF-169-02) : le
 * <b>chemin</b> où l'agent l'a lue et sa <b>taille</b>. Jamais le binaire (Provider-First : l'agent
 * lit par {@code read_file}). Sert la réponse de l'historique ({@code GET /chat}).
 *
 * <p>F-228 / SF-228-01 : son <b>identifiant</b> de dépôt, pour que l'écran en demande l'aperçu
 * ({@code GET /workspaces/{id}/deposits/{depositId}/content}).</p>
 */
public record AtelierAttachedFile(String path, long size, UUID id) {

    public static AtelierAttachedFile of(AtelierDepositedFile file) {
        return new AtelierAttachedFile(file.getPath(), file.getSizeBytes(), file.getId());
    }
}
