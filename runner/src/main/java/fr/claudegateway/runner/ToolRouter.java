package fr.claudegateway.runner;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Aiguillage des outils du runner (F-38 / SF-38-07) : {@code bash} va au {@link BashTool}, les
 * outils {@code teams_*} à la liaison Teams (F-87 / SF-87-03), tout le reste au {@link FileTools}.
 * Une seule ligne de décision, isolée ici pour rester testable — et pour que l'ajout d'un outil ne
 * touche ni l'aiguilleur de trames ni les outils existants.
 */
public final class ToolRouter implements ToolExecutor {

    /** Préfixe commun des outils du volet Teams. Le reste du runner n'en sait pas davantage. */
    static final String TEAMS_PREFIX = "teams_";

    private final FileTools files;
    private final BashTool bash;
    private final fr.claudegateway.runner.teams.TeamsTools teams;

    public ToolRouter(FileTools files, BashTool bash) {
        this(files, bash, null);
    }

    /**
     * @param teams outils du volet Teams, ou {@code null} quand ce runner n'en a pas : un appel
     *              {@code teams_*} est alors refusé comme n'importe quel outil non supporté
     */
    public ToolRouter(FileTools files, BashTool bash,
            fr.claudegateway.runner.teams.TeamsTools teams) {
        this.files = files;
        this.bash = bash;
        this.teams = teams;
    }

    @Override
    public ToolOutcome execute(String tool, JsonNode input, ToolContext context) {
        if (tool != null && tool.startsWith(TEAMS_PREFIX)) {
            return teams == null
                    ? ToolOutcome.error("unsupported_tool",
                            "Le volet Teams n'est pas actif sur cette machine : " + tool)
                    : teams.execute(tool, input, context);
        }
        // F-121 / SF-121-07 : relecture et arrêt d'une commande de fond. La gateway ne les émet que si
        // la capacité bash_background est annoncée ; un montage sans arrière-plan les refuse proprement.
        if ("bash_output".equals(tool)) {
            return bash.outputOf(input);
        }
        if ("kill_shell".equals(tool)) {
            return bash.killOf(input);
        }
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
        // F-121 / SF-121-07 : l'arrière-plan (run_in_background + bash_output/kill_shell) n'est annoncé
        // que si l'exécution est autorisée ET qu'un registre est monté. Sans cette annonce, la gateway
        // sait d'avance de ne pas émettre ces outils, et le comportement d'un runner ancien est intact.
        if (bash.backgroundEnabled()) {
            capabilities.add("bash_background");
        }
        // Teams (F-87 / SF-87-03) : annoncée seulement si la machine l'autorise. Sans elle, la
        // gateway sait d'avance que ce poste ne lira pas Teams, et l'écran le dit sans appeler.
        if (teams != null && teams.enabled()) {
            capabilities.add(fr.claudegateway.runner.teams.TeamsTools.CAPABILITY);
        }
        return List.copyOf(capabilities);
    }
}
