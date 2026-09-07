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
     * Monte la pile et annonce en clair, sur la console, ce que ce montage-ci apporte : la racine
     * confinée, l'interpréteur élu et les exclusions actives (décision D5 — le runner est
     * observable). L'état de l'exécution de commandes, lui, appartient au démarrage et à
     * {@code RunnerMain} seul (SF-38-26, D1).
     */
    public static ToolStack create(RunnerConfig config, Console console, FrameSender sender) {
        ExclusionRules exclusions = ExclusionRules.load(config.workspaceRoot(), console);
        PathGuard guard = new PathGuard(config.workspaceRoot(), exclusions);
        // L'interpréteur est élu ici, une fois, et non redécidé à chaque commande (SF-38-27) : c'est
        // le même point de montage qui garantit que les deux transports exécutent sous le même shell.
        ShellElection shell = ShellElection.elect();
        BashTool bash = new BashTool(guard, config.allowBash(), shell);
        ToolRouter tools = new ToolRouter(new FileTools(guard), bash);

        // Ce bloc annonce ce que RunnerMain ne peut pas connaître : la racine confinée, l'interpréteur
        // élu, les exclusions chargées. Il ne dit RIEN de l'exécution de commandes (SF-38-26, D1) —
        // cet état vient de la configuration, RunnerMain l'a déjà dit, et le répéter ici le disait
        // une fois par transport, en attribuant l'état à `--allow-bash`, sans effet depuis SF-38-19.
        //
        // La répétition des trois lignes ci-dessous au repli long-polling est, elle, VOULUE : elles
        // attestent que le second transport monte les mêmes gardes que la socket, ce qui est la
        // raison d'être de cette classe.
        console.info("Outils fichiers actifs, confinés à : " + config.workspaceRoot());
        console.info("Interpréteur : " + shell.description());
        console.info("Exclusions : " + exclusions.userRuleCount() + " règle(s) issues de "
                + exclusions.source() + " + liste par défaut non désactivable ("
                + String.join(", ", ExclusionRules.DEFAULT_DENY) + ").");

        return new ToolStack(new ToolDispatcher(tools, tools.capabilities(), shell, sender, console));
    }

    public ToolDispatcher dispatcher() {
        return dispatcher;
    }
}
