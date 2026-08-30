package fr.claudegateway.runner;

/**
 * Fabrique la pile d'outils du runner (F-38 / SF-38-09) : exclusions, confinement, outils fichiers,
 * commande, puis l'aiguilleur de trames.
 *
 * <p>Elle existe pour une raison de <b>sécurité</b>, pas de style : les deux transports (WebSocket et
 * repli long-polling) doivent monter <b>exactement</b> les mêmes gardes. Dupliquer ce montage, c'est
 * accepter qu'un jour un chemin oublie le {@link PathGuard} ou les {@link ExclusionRules} — et le
 * confinement (D6) comme les exclusions (D10) ne valent que s'ils sont sans exception.</p>
 */
public final class ToolStack {

    private final ToolDispatcher dispatcher;

    private ToolStack(ToolDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * Monte la pile et annonce en clair, sur la console, ce que la machine expose : la racine, l'état
     * de l'exécution de commandes et les exclusions actives (décision D5 — le runner est observable).
     */
    public static ToolStack create(RunnerConfig config, Console console, FrameSender sender) {
        ExclusionRules exclusions = ExclusionRules.load(config.workspaceRoot(), console);
        PathGuard guard = new PathGuard(config.workspaceRoot(), exclusions);
        BashTool bash = new BashTool(guard, config.allowBash());
        ToolRouter tools = new ToolRouter(new FileTools(guard), bash);

        console.info("Outils fichiers actifs, confinés à : " + config.workspaceRoot());
        console.info(bash.enabled()
                ? "Exécution de commandes ACTIVÉE (--allow-bash) — les commandes tournent avec vos droits."
                : "Exécution de commandes désactivée (relancez avec --allow-bash pour l'autoriser).");
        console.info("Exclusions : " + exclusions.userRuleCount() + " règle(s) issues de "
                + exclusions.source() + " + liste par défaut non désactivable ("
                + String.join(", ", ExclusionRules.DEFAULT_DENY) + ").");

        return new ToolStack(new ToolDispatcher(tools, tools.capabilities(), sender, console));
    }

    public ToolDispatcher dispatcher() {
        return dispatcher;
    }
}
