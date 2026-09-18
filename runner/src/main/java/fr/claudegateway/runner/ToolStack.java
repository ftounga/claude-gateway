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
        // Volet Teams (F-87 / SF-87-03) : monté ici, une fois, et partagé par tous les projets — la
        // liaison au navigateur appartient à la MACHINE. Rien ne se connecte à ce stade : la
        // session ne se rattache qu'au premier appel, et dit alors ce dont elle a besoin (D3).
        fr.claudegateway.runner.teams.TeamsTools teams = config.allowTeams()
                ? new fr.claudegateway.runner.teams.TeamsTools(
                        new fr.claudegateway.runner.teams.TeamsSession(config.teamsPort(),
                                fr.claudegateway.runner.teams.TeamsAdapters.current(),
                                console::info),
                        fr.claudegateway.runner.teams.BrowserLink.realSleeper())
                        .withMoments(moments(config, console))
                        .withCapture(capture(config, console))
                        .withMeetingAudio(meetingAudio(config))
                        .withTranscription(transcription(config, console))
                        // F-100 / SF-100-02 — la synchro du soir : remontée par le jeton du poste.
                        .withRadarUplink(radarUplink(config), console::info)
                        // F-100 / SF-100-05 — le dossier de dépôt du Radar, sous la racine du poste.
                        .withRadarDeposit(config.hostRoot(), console::info)
                        // F-108 / SF-108-03 — les fichiers Microsoft 365 : dossier fixe des
                        // téléchargements, dossiers synchronisés de la machine préférés.
                        .withFiles(new fr.claudegateway.runner.teams.TeamsWorkFolder(
                                config.hostRoot()),
                                fr.claudegateway.runner.teams.SyncedLibraries.detect(),
                                console::info)
                : fr.claudegateway.runner.teams.TeamsTools.disabled(
                        "Le volet Teams est désactivé sur cette machine (--no-teams).");
        ProjectScopes scopes =
                new ProjectScopes(config.hostRoot(), config.allowBash(), shell, console, teams);

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
        console.info(config.allowTeams()
                ? "Teams : la liaison est disponible ; elle observera le navigateur de ce poste sur "
                        + "127.0.0.1:" + config.teamsPort() + " quand on la demandera. Aucun cookie, "
                        + "aucun jeton ne remonte."
                : "Teams : désactivé sur cette machine (--no-teams).");

        return new ToolStack(
                new ToolDispatcher(scopes, scopes.capabilities(), shell, sender, console));
    }

    /**
     * Le travail long des captures (F-90 / SF-90-03), monté ici comme le reste — <b>une fois</b>,
     * partagé par les deux transports.
     *
     * <p>Rien ne se télécharge et rien ne se connecte à ce stade : {@code ffmpeg} n'est cherché
     * qu'au premier travail (D3), et le jeton n'est lu que pour savoir <b>si</b> une remontée est
     * possible. Quand il n'y en a pas, l'uploader le <b>dit</b> au lieu de faire semblant.</p>
     */
    private static fr.claudegateway.runner.teams.MomentsWorker moments(RunnerConfig config,
            Console console) {
        fr.claudegateway.runner.teams.TeamsWorkFolder folder =
                new fr.claudegateway.runner.teams.TeamsWorkFolder(config.hostRoot());
        fr.claudegateway.runner.teams.ProcessRunner processes =
                fr.claudegateway.runner.teams.ProcessRunner.real();
        fr.claudegateway.runner.teams.LocalToolchain toolchain =
                new fr.claudegateway.runner.teams.LocalToolchain(folder, processes, console::info);
        String token = new TokenStore(config.hostRoot(),
                java.nio.file.Path.of(System.getProperty("user.home", "."))).load()
                .map(StoredToken::token).orElse("");
        fr.claudegateway.runner.teams.MomentUploader uploader = token.isBlank()
                ? fr.claudegateway.runner.teams.MomentUploader.unavailable(
                        "ce poste n'a pas de jeton runner : aucune image ne peut remonter")
                : fr.claudegateway.runner.teams.MomentUploader.over(
                        java.net.http.HttpClient.newHttpClient(), config.gatewayBaseUrl(), token);
        return new fr.claudegateway.runner.teams.MomentsWorker(
                new fr.claudegateway.runner.teams.MomentsJobStore(folder),
                new fr.claudegateway.runner.teams.SceneFrames(toolchain, processes), uploader);
    }

    /**
     * <b>La remontée de l'audio d'une réunion</b> (F-128 / SF-128-02) : par le jeton du poste. Sans
     * jeton, elle le <b>dit</b> au lieu de faire semblant.
     */
    private static fr.claudegateway.runner.teams.MeetingAudioUploader meetingAudio(RunnerConfig config) {
        String token = new TokenStore(config.hostRoot(),
                java.nio.file.Path.of(System.getProperty("user.home", "."))).load()
                .map(StoredToken::token).orElse("");
        return token.isBlank()
                ? fr.claudegateway.runner.teams.MeetingAudioUploader.unavailable(
                        "ce poste n'a pas de jeton runner : l'audio ne peut pas remonter")
                : fr.claudegateway.runner.teams.MeetingAudioUploader.over(
                        java.net.http.HttpClient.newHttpClient(), config.gatewayBaseUrl(), token);
    }

    /**
     * <b>La remontée de la synchro du soir</b> (F-100 / SF-100-02) : battement, fin et lots, par le jeton
     * du poste. Sans jeton, elle le <b>dit</b> ({@code NO_UPLINK}) au lieu de faire semblant.
     */
    private static fr.claudegateway.runner.teams.RadarUplink radarUplink(RunnerConfig config) {
        String token = new TokenStore(config.hostRoot(),
                java.nio.file.Path.of(System.getProperty("user.home", "."))).load()
                .map(StoredToken::token).orElse("");
        return fr.claudegateway.runner.teams.RadarUplink.over(java.net.http.HttpClient.newHttpClient(),
                config.gatewayBaseUrl(), token);
    }

    /**
     * <b>L'enregistrement local</b> (F-91 / SF-91-02), monté ici comme le reste — une fois, partagé
     * par les deux transports.
     *
     * <p>Rien ne se télécharge, aucune fenêtre ne s'ouvre et aucun module graphique n'est chargé à
     * ce stade : {@code ffmpeg} n'est cherché qu'au premier enregistrement (D3), et le témoin n'est
     * construit qu'au démarrage d'une capture. Un runner qui n'enregistre jamais — l'immense
     * majorité — ne paie rien de tout cela.</p>
     *
     * <p>Le témoin est <b>branché ici, et pas ailleurs</b> : un moteur monté sans lui capturerait
     * sans le garde-fou n° 3, et c'est exactement le genre d'oubli qu'un montage unique empêche.</p>
     */
    private static fr.claudegateway.runner.teams.LocalCapture capture(RunnerConfig config,
            Console console) {
        fr.claudegateway.runner.teams.TeamsWorkFolder folder =
                new fr.claudegateway.runner.teams.TeamsWorkFolder(config.hostRoot());
        fr.claudegateway.runner.teams.ProcessRunner processes =
                fr.claudegateway.runner.teams.ProcessRunner.real();
        return new fr.claudegateway.runner.teams.LocalCapture(folder,
                new fr.claudegateway.runner.teams.LocalToolchain(folder, processes, console::info),
                processes,
                fr.claudegateway.runner.teams.ProcessSession.real(),
                new fr.claudegateway.runner.teams.CaptureStore(folder),
                fr.claudegateway.runner.teams.BrowserLink.realSleeper(),
                console::info)
                .withWitness(new fr.claudegateway.runner.teams.CaptureWitness());
    }

    /**
     * <b>La transcription locale</b> (F-91 / SF-91-03), montée ici comme le reste.
     *
     * <p>Rien n'est téléchargé à ce stade : le modèle n'est rapatrié qu'à la première transcription
     * (D3). Et <b>rien ne sort de la machine</b> : le seul trafic de ce chemin est ce
     * rapatriement-là, vers une adresse en dur dans le code — jamais l'audio, jamais la vidéo.</p>
     */
    private static fr.claudegateway.runner.teams.TranscriptionWorker transcription(
            RunnerConfig config, Console console) {
        fr.claudegateway.runner.teams.TeamsWorkFolder folder =
                new fr.claudegateway.runner.teams.TeamsWorkFolder(config.hostRoot());
        fr.claudegateway.runner.teams.ProcessRunner processes =
                fr.claudegateway.runner.teams.ProcessRunner.real();
        return fr.claudegateway.runner.teams.TranscriptionWorker.over(
                new fr.claudegateway.runner.teams.LocalToolchain(folder, processes, console::info),
                processes,
                new fr.claudegateway.runner.teams.CaptureStore(folder));
    }

    public ToolDispatcher dispatcher() {
        return dispatcher;
    }
}
