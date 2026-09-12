package fr.claudegateway.runner;

/**
 * Fabrique la pile d'outils du runner (F-38 / SF-38-09) : résolution des chemins, outils fichiers,
 * commande, puis l'aiguilleur de trames.
 *
 * <p>Un seul point de montage, pour que les deux transports (WebSocket et repli long-polling)
 * exécutent <b>exactement</b> la même chose. Dupliquer ce montage, c'est accepter qu'un jour un
 * chemin diverge de l'autre sans que personne ne le voie.</p>
 *
 * <p>Depuis F-48 / SF-48-02, le montage fabrique un {@link ProjectScopes} : la racine passée au
 * runner est celle du <b>poste</b>, et chaque appel désigne le <b>projet</b> où il travaille. Depuis
 * F-73 / SF-73-01, ce projet est un <b>dossier de départ</b> et non une borne — le runner ne promet
 * plus de refuser d'en sortir, il dit ce qu'il fait.</p>
 */
public final class ToolStack {

    private final ToolDispatcher dispatcher;

    private ToolStack(ToolDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    /**
     * Monte la pile et annonce en clair, sur la console, ce que ce montage-ci apporte : la racine du
     * poste et l'interpréteur élu (décision D5 — le runner est observable). L'état de l'exécution de
     * commandes, lui, appartient au démarrage et à {@code RunnerMain} seul (SF-38-26, D1).
     *
     * <p>Ces lignes <b>ne promettent plus de confinement</b> (F-73 / SF-73-01). Elles disaient
     * « chaque tour est confiné au dossier du projet qu'il vise » — c'était faux dès qu'une commande
     * dépassait son premier mot.</p>
     */
    public static ToolStack create(RunnerConfig config, Console console, FrameSender sender) {
        // L'interpréteur est élu ici, une fois, et non redécidé à chaque commande (SF-38-27) : c'est
        // le même point de montage qui garantit que les deux transports exécutent sous le même shell.
        ShellElection shell = ShellElection.elect();
        ProjectScopes scopes = new ProjectScopes(config.hostRoot(), config.allowBash(), shell, console);

        // Ce bloc annonce ce que RunnerMain ne peut pas connaître : la racine du poste et
        // l'interpréteur élu. Il ne dit RIEN de l'exécution de commandes (SF-38-26, D1) — cet état
        // vient de la configuration, RunnerMain l'a déjà dit.
        //
        // La répétition au repli long-polling est, elle, VOULUE : ces lignes attestent que le second
        // transport monte les mêmes gardes que la socket, ce qui est la raison d'être de cette classe.
        console.info("Racine du poste : " + scopes.hostRoot());
        console.info("Le dossier du projet est le point de DÉPART de chaque tour ; il ne borne pas "
                + "ce qu'une commande peut atteindre.");
        console.info("Interpréteur : " + shell.description());
        console.info("Listage : le bruit de construction (node_modules, target, dist…) et le "
                + ".runnerignore de chaque projet sont écartés des listes — ils n'empêchent "
                + "aucune lecture.");

        return new ToolStack(
                new ToolDispatcher(scopes, scopes.capabilities(), shell, sender, console));
    }

    public ToolDispatcher dispatcher() {
        return dispatcher;
    }
}
