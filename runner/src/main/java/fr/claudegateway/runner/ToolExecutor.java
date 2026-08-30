package fr.claudegateway.runner;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Exécution d'un outil runner (F-38 / SF-38-04). Implémentations de production : {@link FileTools}
 * (quatre outils fichiers), {@link BashTool} (commande), assemblées par {@link ToolRouter}.
 * L'interface découple l'aiguillage ({@link ToolDispatcher}) de ce qui touche réellement la machine.
 */
@FunctionalInterface
public interface ToolExecutor {

    /**
     * Exécute un outil. <b>Ne lève pas</b> : toute erreur est rendue sous forme de
     * {@link ToolOutcome} porteur d'un code de la liste close du contrat (§4).
     *
     * @param context contexte d'appel (diffusion de la sortie, délai, annulation) — SF-38-07
     */
    ToolOutcome execute(String tool, JsonNode input, ToolContext context);

    /** Confort : exécution sans diffusion ni délai particulier (outils fichiers, tests). */
    default ToolOutcome execute(String tool, JsonNode input) {
        return execute(tool, input, ToolContext.none());
    }
}
