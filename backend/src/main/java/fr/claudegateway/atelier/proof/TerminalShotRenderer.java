package fr.claudegateway.atelier.proof;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Ce qui transforme le relevé d'une session de preuve en <b>images de terminal</b> (F-212 / SF-212-02).
 * Le relevé est celui du poste (sortie brute, filtrée des secrets par l'appelant) ; l'image imite le
 * terminal par défaut de la plateforme, sans pied ni mention (amendement PO du 2026-10-24).
 */
public interface TerminalShotRenderer {

    /** Une fenêtre photographiée : PNG et ses dimensions en pixels. */
    record Shot(byte[] png, int width, int height) {
    }

    /**
     * @param session relevé : {@code os, user, host, home, execShell, cwd, commands[]} (format du runner)
     * @return une image par fenêtre (la session n'est découpée que si elle est trop haute)
     * @throws fr.claudegateway.diagrams.DiagramRejectedException           relevé refusé par le service
     * @throws fr.claudegateway.diagrams.DiagramRendererUnavailableException service absent ou muet
     */
    List<Shot> shoot(JsonNode session);

    /** Vrai si le service de rendu est configuré. */
    boolean isAvailable();
}
