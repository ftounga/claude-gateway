package fr.claudegateway.runner;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Aiguillage des outils du runner (F-38 / SF-38-07) : {@code bash} va au {@link BashTool}, tout le
 * reste au {@link FileTools}. Une seule ligne de décision, isolée ici pour rester testable — et pour
 * que l'ajout d'un outil ne touche ni l'aiguilleur de trames ni les outils existants.
 */
public final class ToolRouter implements ToolExecutor {

    private final FileTools files;
    private final BashTool bash;

    public ToolRouter(FileTools files, BashTool bash) {
        this.files = files;
        this.bash = bash;
    }

    @Override
    public ToolOutcome execute(String tool, JsonNode input, ToolContext context) {
        return "bash".equals(tool) ? bash.run(input, context) : files.execute(tool, input, context);
    }

    /**
     * Capacités annoncées à la gateway dans la trame {@code ready} (contrat §2.1). {@code bash}
     * n'apparaît que si la machine l'a autorisé : la gateway refuse alors l'appel avant même de
     * l'émettre, et l'utilisateur voit pourquoi.
     */
    public List<String> capabilities() {
        List<String> capabilities = new ArrayList<>();
        capabilities.add("files");
        if (bash.enabled()) {
            capabilities.add("bash");
        }
        return List.copyOf(capabilities);
    }
}
