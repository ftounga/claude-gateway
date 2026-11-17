package fr.claudegateway.atelier.proof;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Le bloc riche « Captures de preuve » du compte rendu de tour (F-212 / SF-212-03) : ce que l'écran
 * affiche (vignettes, zoom, Copier l'image) et relit au rechargement. Les relevés (code, durée, heure,
 * poste, shell) sont pour l'interface, SOUS les vignettes — jamais dans l'image (amendement PO).
 *
 * @param title    titre donné par l'agent
 * @param step     étape du plan prouvée (facultative)
 * @param host     poste (nom affiché par la Forge)
 * @param os       {@code linux}, {@code macos} ou {@code windows}
 * @param shell    shell qui a exécuté la session
 * @param captures les images, dans l'ordre
 * @param commands les commandes de la session et leurs relevés
 * @param files    les extraits de fichiers photographiés (F-268), {@code null} sur un bloc antérieur
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProofBlock(String title, String step, String host, String os, String shell,
        List<Capture> captures, List<Command> commands, List<FileShot> files) {

    /** Une image : son identifiant (route {@code GET /workspaces/{id}/proofs/{captureId}}) et sa taille. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Capture(String id, Integer width, Integer height) {
    }

    /**
     * Un extrait de fichier photographié au rendu éditeur (F-268 / SF-268-02) : relevés pour l'interface.
     *
     * @param captureId  l'image qui le montre
     * @param path       chemin relatif au projet
     * @param firstLine  première ligne montrée
     * @param lastLine   dernière ligne montrée
     * @param totalLines lignes du fichier (lues)
     * @param highlights lignes surlignées
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FileShot(String captureId, String path, int firstLine, int lastLine, int totalLines,
            List<Integer> highlights) {
    }

    /** Une commande de la session : relevés du poste. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Command(String command, String status, Integer exitCode, Long durationMs,
            String startedAt, boolean truncated) {
    }
}
