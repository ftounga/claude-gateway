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
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProofBlock(String title, String step, String host, String os, String shell,
        List<Capture> captures, List<Command> commands) {

    /** Une image : son identifiant (route {@code GET /workspaces/{id}/proofs/{captureId}}) et sa taille. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Capture(String id, Integer width, Integer height) {
    }

    /** Une commande de la session : relevés du poste. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Command(String command, String status, Integer exitCode, Long durationMs,
            String startedAt, boolean truncated) {
    }
}
