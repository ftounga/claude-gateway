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
        return create(config, console, sender, null);
    }

    /**
     * Même montage, avec le <b>Chrome managé partagé</b> (F-128 / SF-128-09) : l'instance unique créée
     * et pilotée par {@code RunnerConnection} (cycle de vie Vigie) est confiée à {@link
     * fr.claudegateway.runner.teams.TeamsTools}, qui la <b>(re)garantit</b> avant qu'un ordre de réunion
     * conclue « injoignable ». {@code null}-safe : le repli long-polling passe {@code null} et le join
     * garde son comportement d'avant.
     */
    public static ToolStack create(RunnerConfig config, Console console, FrameSender sender,
            fr.claudegateway.runner.teams.ManagedChrome managedChrome) {
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
                        .withMeetingImages(meetingImages(config))
                        // F-128 / SF-128-09 — le Chrome managé partagé : le join le (re)garantit avant
                        // de conclure « injoignable » (null-safe : repli long-polling inchangé).
                        .withManagedChrome(managedChrome)
                        .withTranscription(transcription(config, console))
                        // F-100 / SF-100-02 — la synchro du soir : remontée par le jeton du poste.
                        .withRadarUplink(radarUplink(config), console::info)
                        // F-147 / SF-147-02 — le texte d'un enregistrement rejoint sa réunion.
                        .withLocalTranscript(localTranscript(config))
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
                new ProjectScopes(config.hostRoot(), config.allowBash(), shell, console, teams)
                        .withVideo(video(config, console))
                        .withRender(render(config, console));

        // Ce bloc annonce ce que RunnerMain ne peut pas connaître : la racine du poste et
        // l'interpréteur élu. Il ne dit RIEN de l'exécution de commandes (SF-38-26, D1) — cet état
        // vient de la configuration, RunnerMain l'a déjà dit.
        //
        // La répétition au repli long-polling est, elle, VOULUE : ces lignes attestent que le second
        // transport monte les mêmes gardes que la socket, ce qui est la raison d'être de cette classe.
        // Reap des worktrees `task` orphelins au démarrage (F-150 / SF-150-01, D9) : une déconnexion
        // a pu tuer un tour et laisser un worktree sous .atelier-worktrees/. Best-effort, jamais bloquant.
        WorktreeTool.reapAtStartup(scopes.hostRoot(), console);

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
     * <b>Regarder une vidéo</b> (F-190 / SF-190-01) : monté sur toute machine, Teams ou pas. Rien
     * ne se télécharge ici — {@code ffmpeg} n'est cherché qu'à la première extraction, dans le même
     * cache que F-90 ; depuis F-215 / SF-215-06, la chaîne vidéo du portail passe AVANT toute source
     * internet (gyan.dev, evermeet.cx, johnvansickle.com ne restent qu'en repli).
     */
    private static fr.claudegateway.runner.video.VideoTools video(RunnerConfig config,
            Console console) {
        fr.claudegateway.runner.teams.ProcessRunner processes =
                fr.claudegateway.runner.teams.ProcessRunner.real();
        fr.claudegateway.runner.teams.TeamsWorkFolder folder =
                new fr.claudegateway.runner.teams.TeamsWorkFolder(config.hostRoot());
        fr.claudegateway.runner.teams.LocalToolchain toolchain =
                new fr.claudegateway.runner.teams.LocalToolchain(folder, processes, console::info)
                        .withPortal(portalMedia(videoBundle(config, folder.toolsDir(),
                                embeddedSignature(),
                                storedToken(config))));
        return new fr.claudegateway.runner.video.VideoTools(
                new fr.claudegateway.runner.video.VideoFramesWorker(
                        new fr.claudegateway.runner.teams.SceneFrames(toolchain, processes),
                        // SF-190-03 : transcrire sur le poste quand on n'a pas de transcription.
                        fr.claudegateway.runner.video.VideoTranscriber.local(toolchain, processes)));
    }

    /**
     * <b>Rendre une vidéo</b> (F-215 / SF-215-02, SF-215-06) : monté sur toute machine ; rien ne se télécharge ici.
     * Au premier rendu, la chaîne (Node 22, hyperframes, GSAP, ffmpeg) vient du PORTAIL — jamais d'internet — et le
     * navigateur est le Chrome ou l'Edge du poste.
     */
    private static fr.claudegateway.runner.video.render.RenderTools render(RunnerConfig config,
            Console console) {
        return render(config, embeddedSignature(),
                storedToken(config));
    }

    /**
     * Le montage du rendu, la vérification de signature et le jeton injectés : la clé embarquée et le jeton du poste
     * en production ; une clé et un jeton d'essai pour la preuve réelle hors ligne ({@code VideoToolchainLiveTest}).
     */
    static fr.claudegateway.runner.video.render.RenderTools render(RunnerConfig config,
            java.util.function.BiPredicate<byte[], String> signatureCheck,
            java.util.function.Supplier<String> token) {
        fr.claudegateway.runner.teams.TeamsWorkFolder folder =
                new fr.claudegateway.runner.teams.TeamsWorkFolder(config.hostRoot());
        // Ni rapatriement ni portail ici : ffmpeg du poste n'est retenu que s'il est DÉJÀ là ; sinon c'est
        // celui de la chaîne du portail.
        fr.claudegateway.runner.teams.LocalToolchain toolchain =
                new fr.claudegateway.runner.teams.LocalToolchain(folder,
                        fr.claudegateway.runner.teams.ProcessRunner.real(), message -> { });
        fr.claudegateway.runner.video.render.RenderToolchain.Media media =
                new fr.claudegateway.runner.video.render.RenderToolchain.Media() {
                    @Override
                    public java.nio.file.Path ffmpeg() {
                        return toolchain.require(fr.claudegateway.runner.teams.LocalTool.ffmpeg());
                    }

                    @Override
                    public java.nio.file.Path ffprobe() {
                        return toolchain.require(fr.claudegateway.runner.teams.LocalTool.ffprobe());
                    }

                    @Override
                    public boolean presentWithoutDownload() {
                        return toolchain.availableWithoutDownload(fr.claudegateway.runner.teams.LocalTool.ffmpeg())
                                && toolchain.availableWithoutDownload(
                                        fr.claudegateway.runner.teams.LocalTool.ffprobe());
                    }
                };
        fr.claudegateway.runner.video.render.RenderProcesses processes =
                fr.claudegateway.runner.video.render.RenderProcesses.real();
        return new fr.claudegateway.runner.video.render.RenderTools(
                new fr.claudegateway.runner.video.render.RenderToolchain(folder.toolsDir(),
                        OperatingSystem.current(), processes,
                        videoBundle(config, folder.toolsDir(), signatureCheck, token),
                        fr.claudegateway.runner.teams.LocalToolchain::lookupOnPath, media,
                        system -> fr.claudegateway.runner.teams.ChromePaths.executable(system, System::getenv,
                                java.nio.file.Files::isRegularFile),
                        System.getenv("PATH")),
                processes);
    }

    /**
     * La chaîne vidéo du portail (F-215 / SF-215-06) : par le client réseau du runner (proxy d'entreprise, racine
     * Zscaler), authentifiée par le jeton du poste, lu au moment de servir (un poste apparié après le démarrage
     * en profite).
     */
    private static fr.claudegateway.runner.video.render.VideoToolchainBundle videoBundle(RunnerConfig config,
            java.nio.file.Path toolsDir, java.util.function.BiPredicate<byte[], String> signatureCheck,
            java.util.function.Supplier<String> token) {
        return new fr.claudegateway.runner.video.render.VideoToolchainBundle(toolsDir, OperatingSystem.current(),
                System.getProperty("os.arch"), config.gatewayBaseUrl(), token, RunnerHttp::client, signatureCheck);
    }

    /** ffmpeg et ffprobe pris dans la chaîne vidéo du portail (F-190 profite de SF-215-06). */
    private static fr.claudegateway.runner.teams.LocalToolchain.PortalSource portalMedia(
            fr.claudegateway.runner.video.render.VideoToolchainBundle bundle) {
        return (tool, say) -> {
            boolean ffmpeg = "ffmpeg".equals(tool.name());
            if ((!ffmpeg && !"ffprobe".equals(tool.name())) || !bundle.supported()) {
                return java.util.Optional.empty();
            }
            fr.claudegateway.runner.video.render.VideoToolchainBundle.Installed installed =
                    bundle.installed().orElseGet(() -> bundle.ensure(say));
            return java.util.Optional.of(ffmpeg ? installed.ffmpeg() : installed.ffprobe());
        };
    }

    /**
     * La signature du manifeste vérifiée avec la clé F-111 embarquée — chargée au moment de vérifier, jamais au
     * montage : une clé illisible refuse le paquet, elle n'empêche pas le runner de démarrer.
     */
    private static java.util.function.BiPredicate<byte[], String> embeddedSignature() {
        return (bytes, signature) -> {
            try {
                return fr.claudegateway.runner.update.UpdateVerifier.embedded().signatureValid(bytes, signature);
            } catch (IllegalStateException e) {
                return false;
            }
        };
    }

    /** Le jeton du poste, relu à chaque demande. */
    private static java.util.function.Supplier<String> storedToken(RunnerConfig config) {
        return () -> new TokenStore(config.hostRoot(),
                java.nio.file.Path.of(System.getProperty("user.home", "."))).load()
                .map(StoredToken::token).orElse("");
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
                        RunnerHttp.client(), config.gatewayBaseUrl(), token);
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
                        RunnerHttp.client(), config.gatewayBaseUrl(), token);
    }

    /**
     * <b>La remontée du texte d'un enregistrement déposé</b> (F-147 / SF-147-02), par le jeton du poste.
     * Sans jeton, elle le <b>dit</b> : le texte reste sur la machine.
     */
    private static fr.claudegateway.runner.teams.LocalTranscriptUploader localTranscript(RunnerConfig config) {
        String token = new TokenStore(config.hostRoot(),
                java.nio.file.Path.of(System.getProperty("user.home", "."))).load()
                .map(StoredToken::token).orElse("");
        return token.isBlank()
                ? fr.claudegateway.runner.teams.LocalTranscriptUploader.unavailable(
                        "ce poste n'a pas de jeton runner : le texte ne peut pas remonter")
                : fr.claudegateway.runner.teams.LocalTranscriptUploader.over(
                        RunnerHttp.client(), config.gatewayBaseUrl(), token);
    }

    /** <b>La remontée des images clés d'une réunion</b> (F-128 / SF-128-03), par le jeton du poste. */
    private static fr.claudegateway.runner.teams.MeetingImageUploader meetingImages(RunnerConfig config) {
        String token = new TokenStore(config.hostRoot(),
                java.nio.file.Path.of(System.getProperty("user.home", "."))).load()
                .map(StoredToken::token).orElse("");
        return token.isBlank()
                ? fr.claudegateway.runner.teams.MeetingImageUploader.unavailable(
                        "ce poste n'a pas de jeton runner : les images ne peuvent pas remonter")
                : fr.claudegateway.runner.teams.MeetingImageUploader.over(
                        RunnerHttp.client(), config.gatewayBaseUrl(), token);
    }

    /**
     * <b>La remontée de la synchro du soir</b> (F-100 / SF-100-02) : battement, fin et lots, par le jeton
     * du poste. Sans jeton, elle le <b>dit</b> ({@code NO_UPLINK}) au lieu de faire semblant.
     */
    private static fr.claudegateway.runner.teams.RadarUplink radarUplink(RunnerConfig config) {
        String token = new TokenStore(config.hostRoot(),
                java.nio.file.Path.of(System.getProperty("user.home", "."))).load()
                .map(StoredToken::token).orElse("");
        return fr.claudegateway.runner.teams.RadarUplink.over(RunnerHttp.client(),
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
