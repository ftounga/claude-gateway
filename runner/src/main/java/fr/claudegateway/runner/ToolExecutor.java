package fr.claudegateway.runner;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Exécution d'un outil runner (F-38 / SF-38-04). Implémentation de production : {@link FileTools}.
 * L'interface découple l'aiguillage ({@link ToolDispatcher}) de l'accès au système de fichiers, et
 * ouvre la porte à l'outil {@code bash} de SF-38-07 sans toucher au dispatcher.
 */
@FunctionalInterface
public interface ToolExecutor {

    /**
     * Exécute un outil. <b>Ne lève pas</b> : toute erreur est rendue sous forme de
     * {@link ToolOutcome} porteur d'un code de la liste close du contrat (§4).
     */
    ToolOutcome execute(String tool, JsonNode input);
}
