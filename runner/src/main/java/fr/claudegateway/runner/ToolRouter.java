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
    private final WorktreeTool worktree;
    /** Regarder une vidéo (F-190 / SF-190-01), ou {@code null} : l'appel est alors refusé. */
    private final fr.claudegateway.runner.video.VideoTools.Bound video;
    /** Rendre une vidéo (F-215 / SF-215-02), ou {@code null} : l'appel est alors refusé. */
    private final fr.claudegateway.runner.video.render.RenderTools.Bound render;

    public ToolRouter(FileTools files, BashTool bash) {
        this(files, bash, null, null);
    }

    public ToolRouter(FileTools files, BashTool bash,
            fr.claudegateway.runner.teams.TeamsTools teams) {
        this(files, bash, teams, null);
    }

    /**
     * @param teams    outils du volet Teams, ou {@code null} quand ce runner n'en a pas : un appel
     *                 {@code teams_*} est alors refusé comme n'importe quel outil non supporté
     * @param worktree cycle de vie des worktrees isolés de {@code task} (F-150 / SF-150-01), ou
     *                 {@code null} : un appel {@code worktree_*} est alors refusé en
     *                 {@code unsupported_tool} — exactement ce que fait un runner antérieur
     */
    public ToolRouter(FileTools files, BashTool bash,
            fr.claudegateway.runner.teams.TeamsTools teams, WorktreeTool worktree) {
        this(files, bash, teams, worktree, null);
    }

    /**
     * @param video outils vidéo liés au projet (F-190 / SF-190-01), ou {@code null} : la capacité
     *              {@code video} n'est alors pas annoncée et la gateway ne les déclare pas
     */
    public ToolRouter(FileTools files, BashTool bash,
            fr.claudegateway.runner.teams.TeamsTools teams, WorktreeTool worktree,
            fr.claudegateway.runner.video.VideoTools.Bound video) {
        this(files, bash, teams, worktree, video, null);
    }

    /**
     * @param render rendu vidéo lié au projet (F-215 / SF-215-02), ou {@code null} : la capacité
     *               {@code video_render} n'est alors pas annoncée
     */
    public ToolRouter(FileTools files, BashTool bash,
            fr.claudegateway.runner.teams.TeamsTools teams, WorktreeTool worktree,
            fr.claudegateway.runner.video.VideoTools.Bound video,
            fr.claudegateway.runner.video.render.RenderTools.Bound render) {
        this.render = render;
        this.video = video;
        this.files = files;
        this.bash = bash;
        this.teams = teams;
        this.worktree = worktree;
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
        // Rendre une vidéo (F-215 / SF-215-02) : il lance des programmes — refusé si l'exécution de
        // commandes est interdite sur ce poste, comme bash.
        if (fr.claudegateway.runner.video.render.RenderTools.handles(tool)) {
            if (render == null) {
                return ToolOutcome.error("unsupported_tool",
                        "Ce runner ne sait pas rendre de vidéo : mettez-le à jour (" + tool + ").");
            }
            if (!bash.enabled()) {
                return ToolOutcome.error("unsupported_tool",
                        "L'exécution de commandes est désactivée sur ce poste : le rendu vidéo aussi.");
            }
            return render.execute(tool, input, context);
        }
        // Regarder une vidéo (F-190 / SF-190-01) : indépendant du volet Teams.
        if (fr.claudegateway.runner.video.VideoTools.handles(tool)) {
            return video == null
                    ? ToolOutcome.error("unsupported_tool",
                            "Ce runner ne sait pas extraire les images d'une vidéo : " + tool)
                    : video.execute(tool, input, context);
        }
        // Worktrees isolés de `task` (F-150 / SF-150-01). Un runner sans ce montage — ou un runner
        // antérieur — n'entre pas ici : l'appel file au `unsupported_tool` de FileTools, ce qui est
        // exactement la rétro-compatibilité attendue.
        if (WorktreeTool.handles(tool) && worktree != null) {
            return worktree.execute(tool, input, context);
        }
        // Captures de preuve (F-212 / SF-212-01) : une session de commandes dans un même shell.
        if (ProofRun.TOOL.equals(tool)) {
            return bash.proofRun(input, context);
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
            // F-195 / SF-195-02 : combien de commandes ce poste accepte à la fois. Sans cette annonce
            // (runner ancien), la gateway compte 1 et n'envoie rien en parallèle.
            capabilities.add(BashTool.PARALLEL_CAPABILITY_PREFIX + bash.concurrency());
            // F-212 / SF-212-01 : sessions de preuve (proof_run). Sans cette annonce (runner ancien),
            // la gateway répond « mettez à jour le poste » au lieu d'émettre l'appel.
            capabilities.add(ProofRun.CAPABILITY);
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
        // F-190 / SF-190-01 : annoncée dès que les outils vidéo sont montés, Teams ou pas.
        if (video != null) {
            capabilities.add(fr.claudegateway.runner.video.VideoTools.CAPABILITY);
        }
        // F-215 / SF-215-02 : le rendu lance des programmes — annoncé seulement si bash est autorisé.
        if (render != null && bash.enabled()) {
            capabilities.add(fr.claudegateway.runner.video.render.RenderTools.CAPABILITY);
        }
        return List.copyOf(capabilities);
    }
}
