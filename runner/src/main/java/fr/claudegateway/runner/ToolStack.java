package fr.claudegateway.runner;

/**
 * Fabrique la pile d'outils du runner (F-38 / SF-38-09) : confinement, outils fichiers, commande,
 * puis l'aiguilleur de trames.
 *
 * <p>Elle existe pour une raison de <b>sécurité</b>, pas de style : les deux transports (WebSocket et
 * repli long-polling) doivent monter <b>exactement</b> les mêmes gardes. Dupliquer ce montage, c'est
 * accepter qu'un jour un chemin oublie le {@link PathGuard} ou les {@link ExclusionRules} — et le
 * confinement (D6) comme les exclusions (D10) ne valent que s'ils sont sans exception.</p>
 *
 * <p>Depuis F-48 / SF-48-02, le montage ne fabrique plus <b>un</b> confinement mais un
 * {@link ProjectScopes} : la racine passée au runner est celle du <b>poste</b>, et la garde se
 * referme sur le <b>projet</b> que chaque appel désigne. C'est ici que la promesse « un processus
 * local refuse lui-même de sortir » reste vraie, projet par projet.</p>
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
     * <p>Les <b>exclusions</b> ne sont plus annoncées ici : elles sont désormais chargées par projet,
     * dans le dossier du projet, au premier appel qui l'ouvre. Les annoncer au démarrage
     * reviendrait à décrire un fichier qu'on n'a pas encore lu.</p>
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
        console.info("Chaque tour est confiné au dossier du projet qu'il vise, sous cette racine.");
        console.info("Interpréteur : " + shell.description());
        console.info("Exclusions : liste par défaut non désactivable ("
                + String.join(", ", ExclusionRules.DEFAULT_DENY)
                + "), plus le .runnerignore de chaque projet.");

        return new ToolStack(
                new ToolDispatcher(scopes, scopes.capabilities(), shell, sender, console));
    }

    public ToolDispatcher dispatcher() {
        return dispatcher;
    }
}
