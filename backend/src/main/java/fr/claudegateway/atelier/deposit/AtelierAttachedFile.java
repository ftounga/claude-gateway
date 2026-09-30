package fr.claudegateway.atelier.deposit;

/**
 * Une pièce jointe telle qu'elle est rendue dans la bulle d'un message (F-169 / SF-169-02) : le
 * <b>chemin</b> où l'agent l'a lue et sa <b>taille</b>. Jamais le binaire (Provider-First : l'agent
 * lit par {@code read_file}). Sert la réponse de l'historique ({@code GET /chat}).
 */
public record AtelierAttachedFile(String path, long size) {

    public static AtelierAttachedFile of(AtelierDepositedFile file) {
        return new AtelierAttachedFile(file.getPath(), file.getSizeBytes());
    }
}
