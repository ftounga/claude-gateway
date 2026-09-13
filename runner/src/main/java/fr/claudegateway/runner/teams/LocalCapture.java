package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Supplier;

import fr.claudegateway.runner.OperatingSystem;

/**
 * <b>L'enregistrement local</b> (F-91 / SF-91-01) : le moteur qui démarre, refuse et arrête une
 * capture d'écran sur la machine.
 *
 * <h2>Ce morceau n'est pas de la même nature que les autres, et ce fichier le dit</h2>
 *
 * <p>Tout le reste du volet Teams <b>relit ce qui existait déjà</b>. Celui-ci <b>crée</b> — et les
 * participants ne le sauront pas, là où Teams affiche un bandeau quand c'est lui qui enregistre.
 * Article <b>226-1 du code pénal</b>, politiques internes des clients, et — c'est ce qui décide de la
 * conception — <b>la personne exposée est le consultant</b>, pas la gateway. Le PO a maintenu la
 * demande après exposition du risque ; le produit la sert, en rendant les garde-fous
 * <b>structurels</b> plutôt que recommandés.</p>
 *
 * <h2>Les trois garde-fous, et où ils sont dans ce fichier</h2>
 *
 * <ol>
 *   <li><b>La trace est indélébile.</b> {@link #start} refuse de lancer {@code ffmpeg} tant qu'il
 *       n'a pas une police <b>et</b> le filtre {@code drawtext} : pas de filigrane, pas de capture.
 *       Le filigrane est incrusté <b>dans l'image</b>, la mention voyage avec le compte rendu, et
 *       l'appel d'outil laisse une ligne au journal d'audit de la gateway.</li>
 *   <li><b>Deux usages, deux gestes.</b> {@link CaptureConsent} tranche <b>avant</b> qu'on ait
 *       cherché quoi que ce soit : un refus ne laisse rien derrière lui.</li>
 *   <li><b>Un témoin au premier plan.</b> Il est en SF-91-02, et il est branché ici par
 *       {@link Witness} — parce que c'est <b>le moteur</b> qui sait quand la capture commence et
 *       finit, et qu'un témoin branché ailleurs finirait par se désynchroniser.</li>
 * </ol>
 *
 * <h2>Hors périmètre, par écrit</h2>
 *
 * <p>Capturer à l'insu de l'utilisateur du poste, et toute capture déclenchée autrement que par un
 * geste explicite. Il n'existe aucun chemin dans cette classe qui démarre une capture sans un appel
 * d'outil nommant son usage.</p>
 */
public final class LocalCapture {

    /** Le temps qu'on laisse à {@code ffmpeg} pour mourir s'il doit mourir tout de suite. */
    static final long STARTUP_GRACE_MS = 1_500L;
    /** Le temps qu'on laisse à l'arrêt propre avant d'interrompre. */
    static final long STOP_GRACE_MS = 15_000L;
    /** Délai de la question « quels filtres connais-tu ? ». */
    static final long FILTERS_TIMEOUT_MS = 20_000L;
    /** Nom du filtre sans lequel il n'y a pas de filigrane. */
    static final String DRAWTEXT = "drawtext";

    private final TeamsWorkFolder folder;
    private final OperatingSystem os;
    private final LocalToolchain toolchain;
    private final ProcessRunner processes;
    private final ProcessSession sessions;
    private final CaptureStore store;
    private final WatermarkFont fonts;
    private final BrowserLink.Sleeper sleeper;
    private final Supplier<Instant> clock;
    private final Consumer<String> say;
    private final Supplier<String> display;

    private volatile Witness witness = Witness.none();
    private volatile CaptureCeiling ceiling = new CaptureCeiling();
    private volatile Live live;
    private volatile Boolean drawtextKnown;

    public LocalCapture(TeamsWorkFolder folder, LocalToolchain toolchain, ProcessRunner processes,
            ProcessSession sessions, CaptureStore store, BrowserLink.Sleeper sleeper,
            Consumer<String> say) {
        this(folder, OperatingSystem.current(), toolchain, processes, sessions, store,
                new WatermarkFont(OperatingSystem.current()), sleeper, Instant::now, say,
                () -> System.getenv("DISPLAY"));
    }

    LocalCapture(TeamsWorkFolder folder, OperatingSystem os, LocalToolchain toolchain,
            ProcessRunner processes, ProcessSession sessions, CaptureStore store,
            WatermarkFont fonts, BrowserLink.Sleeper sleeper, Supplier<Instant> clock,
            Consumer<String> say, Supplier<String> display) {
        this.folder = folder;
        this.os = os == null ? OperatingSystem.OTHER : os;
        this.toolchain = toolchain;
        this.processes = processes;
        this.sessions = sessions;
        this.store = store;
        this.fonts = fonts;
        this.sleeper = sleeper == null ? millis -> { } : sleeper;
        this.clock = clock == null ? Instant::now : clock;
        this.say = say == null ? message -> { } : say;
        this.display = display == null ? () -> "" : display;
    }

    /**
     * Branche le témoin au premier plan (SF-91-02). Posé après construction parce que le témoin
     * dépend de l'environnement graphique, que le montage des outils ne connaît pas.
     */
    public LocalCapture withWitness(Witness value) {
        this.witness = value == null ? Witness.none() : value;
        return this;
    }

    /** Remplace l'arrêt de sécurité (SF-91-02). Sert aux tests, qui n'attendent pas trois heures. */
    LocalCapture withCeiling(CaptureCeiling value) {
        this.ceiling = value == null ? new CaptureCeiling() : value;
        return this;
    }

    /** Le plafond de durée, pour le dire au démarrage. */
    public CaptureCeiling ceiling() {
        return ceiling;
    }

    // ------------------------------------------------------------------ démarrer

    /**
     * Démarre une capture, ou <b>refuse</b>.
     *
     * <p>L'ordre des vérifications n'est pas indifférent : le <b>consentement d'abord</b>, parce
     * qu'un refus ne doit rien laisser derrière lui — ni binaire téléchargé, ni dossier créé, ni
     * processus lancé.</p>
     *
     * @throws CaptureRefusedException toujours avec son remède
     */
    public CaptureRecord start(Request request) {
        // 1. Le second geste. AVANT tout le reste.
        CaptureConsent consent = CaptureConsent.require(request.purpose(),
                request.participantsInformed());

        // 2. Une seule capture à la fois : deux fichiers lourds en parallèle, dont l'un serait
        //    oublié, c'est exactement la capture oubliée que le garde-fou n° 3 cherche à éviter.
        CaptureRecord already = current().orElse(null);
        if (already != null) {
            throw new CaptureRefusedException(CaptureRefusedException.ALREADY_RUNNING,
                    "Une capture tourne déjà depuis " + CaptureRecord.clock(
                            already.elapsed(clock.get())) + " (" + already.purpose().label() + ").",
                    "Arrêtez-la d'abord — le témoin au premier plan a un bouton, et « "
                            + "teams_capture_stop » fait la même chose. Son identifiant est « "
                            + already.id() + " ».");
        }

        // 3. LE TÉMOIN, avant tout le reste aussi : sans lui, le garde-fou n° 3 n'existe plus, et
        //    un poste sans écran n'a de toute façon rien à capturer. Demandé ICI pour qu'un refus
        //    ne laisse ni binaire rapatrié, ni dossier créé.
        try {
            witness.requireAvailable();
        } catch (CaptureWitnessException e) {
            throw new CaptureRefusedException(CaptureRefusedException.NO_WITNESS, e.getMessage(),
                    e.remedy(), e);
        }

        // 4. L'outillage (D3) : PATH, copie rapatriée, puis seulement téléchargement.
        Path ffmpeg;
        try {
            ffmpeg = toolchain.require(LocalTool.ffmpeg());
        } catch (ToolchainUnavailableException e) {
            throw new CaptureRefusedException(CaptureRefusedException.NO_TOOL, e.getMessage(),
                    e.remedy(), e);
        }

        // 5. LE FILIGRANE, ET LE REFUS QUI VA AVEC. Un enregistrement anonyme ne se fait pas.
        requireDrawtext(ffmpeg);
        Path font = fonts.find();
        if (font == null) {
            throw new CaptureRefusedException(CaptureRefusedException.NO_WATERMARK,
                    "Je ne trouve aucune police utilisable sur ce poste, donc je ne peux pas "
                            + "incruster le filigrane — et sans filigrane, je ne capture pas : un "
                            + "enregistrement doit toujours pouvoir dire qui l'a fait.",
                    fonts.remedy());
        }

        // 6. Les entrées du poste. Peut refuser (son inconnu sous Windows, système inconnu).
        CaptureDevices devices = CaptureDevices.resolve(os, request.audio(),
                request.screenDevice(), request.audioDevice(), display.get());

        Instant startedAt = clock.get();
        Watermark watermark = new Watermark(request.identity(), startedAt, consent.purpose());
        String id = newId();
        Path video;
        List<String> command;
        try {
            video = store.captureDir(id).resolve("capture.mp4");
            command = ScreenCaptureCommand.build(ffmpeg, os, devices,
                    watermark.drawtextFilter(font), video);
        } catch (IOException e) {
            throw new CaptureRefusedException(CaptureRefusedException.NOT_STARTED,
                    "Le dossier de la capture n'a pas pu être préparé sur cette machine.",
                    "Vérifiez l'espace disque et les droits sur " + folder.capturesDir() + ".", e);
        }

        CaptureRecord record = new CaptureRecord(id, consent, video.toString(), watermark)
                .startedAt(startedAt)
                .devices(devices)
                .subject(request.subject());

        ProcessSession.Handle handle;
        try {
            handle = sessions.start(command, video.getParent());
        } catch (IOException e) {
            throw new CaptureRefusedException(CaptureRefusedException.NOT_STARTED,
                    "La capture n'a pas pu être lancée sur cette machine.",
                    "Vérifiez qu'" + ffmpeg + " est exécutable, puis redemandez. " + devices.describe(),
                    e);
        }

        // 7. Une mort immédiate est la règle plutôt que l'exception : périphérique refusé, X
        //    inaccessible, autorisation d'enregistrement d'écran non accordée sous macOS. On la
        //    constate ICI, pendant que l'appel est encore là pour la dire — et on rend LES
        //    DERNIÈRES LIGNES d'ffmpeg, jamais un « échec » nu.
        sleeper.sleep(STARTUP_GRACE_MS);
        if (!handle.alive()) {
            handle.destroy();
            throw new CaptureRefusedException(CaptureRefusedException.NOT_STARTED,
                    "La capture s'est arrêtée aussitôt lancée. " + devices.describe(),
                    tail(handle));
        }

        // 8. LE TÉMOIN. Il ne prévient que l'utilisateur du poste, ET C'EST SON BUT : éviter la
        //    capture oubliée qui tourne trois heures. Ce qu'il n'a pas pu faire est NOMMÉ.
        live = new Live(record, handle);
        try {
            record.addGaps(witness.show(record, () -> stopQuietly(id)));
        } catch (CaptureWitnessException e) {
            // La fenêtre a refusé de se construire APRÈS le lancement : on n'enregistre pas sans
            // témoin, donc on défait ce qu'on vient de faire plutôt que de continuer sans lui.
            handle.destroy();
            live = null;
            throw new CaptureRefusedException(CaptureRefusedException.NO_WITNESS, e.getMessage(),
                    e.remedy(), e);
        }

        // 9. L'arrêt de sécurité, armé maintenant et DIT maintenant : une limite qu'on apprend en
        //    la heurtant est une panne.
        ceiling.arm(() -> stopAtCeiling(id));
        store.save(record);
        say.accept(consent.describe() + " " + ceiling.sentence() + " "
                + record.describe(clock.get()));
        return record;
    }

    // ------------------------------------------------------------------ arrêter

    /**
     * Arrête une capture et rend son état final.
     *
     * @param id l'identifiant, ou {@code ""} pour <b>celle qui tourne</b>
     * @throws CaptureRefusedException quand aucune capture ne correspond
     */
    public CaptureRecord stop(String id) {
        Live running = live;
        String wanted = id == null ? "" : id.strip();
        if (running == null || (!wanted.isEmpty() && !wanted.equals(running.record().id()))) {
            CaptureRecord known = wanted.isEmpty() ? null : store.find(wanted).orElse(null);
            if (known != null && known.isOver()) {
                // Redemander l'arrêt d'une capture déjà arrêtée n'est pas une erreur : on rend son
                // état plutôt que de faire comme si elle n'existait pas.
                return known;
            }
            throw new CaptureRefusedException(CaptureRefusedException.UNKNOWN,
                    wanted.isEmpty() ? "Aucune capture ne tourne sur cette machine."
                            : "Je ne connais aucune capture en cours sous l'identifiant « " + wanted
                                    + " ».",
                    "Demandez l'état des captures : celle qui tourne, s'il y en a une, y sera — et "
                            + "elle s'arrête aussi d'un clic sur le témoin au premier plan.");
        }
        return finish(running);
    }

    /** La capture en cours, en mémoire ou relue du disque. */
    public Optional<CaptureRecord> current() {
        Live running = live;
        if (running != null && !running.record().isOver()) {
            return Optional.of(running.record());
        }
        // Un runner redémarré pendant une capture : l'état survit, même si le processus, lui, est
        // parti avec l'ancien runner. On le DIT plutôt que de prétendre pouvoir l'arrêter.
        return store.running();
    }

    /** La capture de cet identifiant. */
    public Optional<CaptureRecord> find(String id) {
        Live running = live;
        if (running != null && running.record().id().equals(id == null ? "" : id.strip())) {
            return Optional.of(running.record());
        }
        return store.find(id);
    }

    /** Toutes les captures connues de cette machine, de la plus récente à la plus ancienne. */
    public List<CaptureRecord> all() {
        return store.all();
    }

    /**
     * L'arrêt demandé <b>par le témoin</b> (SF-91-02) : il ne peut pas lever, parce qu'il n'y a
     * personne pour lire l'exception derrière un bouton. Un second clic sur un témoin resté ouvert
     * ne doit pas non plus faire remonter quoi que ce soit.
     */
    void stopQuietly(String id) {
        try {
            stop(id);
        } catch (RuntimeException e) {
            say.accept("L'arrêt demandé depuis le témoin n'a pas abouti : " + e.getMessage());
        }
    }

    /**
     * <b>L'arrêt de sécurité</b> (SF-91-02) : au bout du plafond, la capture s'arrête d'elle-même.
     *
     * <p>Elle ne s'arrête pas en silence : le manque est <b>nommé dans l'état</b>, pour que le
     * compte rendu qui suivra dise que l'enregistrement a été coupé — et non qu'il couvre toute la
     * réunion.</p>
     */
    void stopAtCeiling(String id) {
        Live running = live;
        if (running == null || !running.record().id().equals(id)) {
            return;
        }
        running.record().addGap(new TeamsGap(TeamsGapKind.CAP_REACHED, "durée de la capture",
                "l'enregistrement a atteint le plafond de " + CaptureRecord.clock(ceiling.max())
                        + " et s'est arrêté de lui-même : ce qui s'est passé après n'y est pas", 1));
        say.accept("Plafond de durée atteint : j'arrête cet enregistrement de moi-même.");
        stopQuietly(id);
    }

    // ------------------------------------------------------------------ interne

    private CaptureRecord finish(Live running) {
        CaptureRecord record = running.record();
        ProcessSession.Handle handle = running.handle();
        // L'arrêt PROPRE d'abord : tuer ffmpeg laisse un conteneur sans index, que presque aucun
        // lecteur n'ouvre — 400 Mo qui ressemblent à un enregistrement sans en être un.
        handle.requestStop();
        int exit = handle.awaitExit(STOP_GRACE_MS);
        if (exit < 0 && handle.alive()) {
            handle.destroy();
            record.addGap(new TeamsGap(TeamsGapKind.UNRECOGNIZED_PAYLOAD, "arrêt de la capture",
                    "ffmpeg n'a pas répondu à l'arrêt propre et a été interrompu : le fichier peut "
                            + "être illisible par endroits", 1));
            handle.awaitExit(2_000L);
        }
        live = null;
        ceiling.disarm();
        witness.hide();

        Path video = Path.of(record.video());
        long size = sizeOf(video);
        Instant now = clock.get();
        if (size <= 0L) {
            record.failed(now,
                    "La capture n'a produit aucun fichier exploitable.",
                    "Ce qu'ffmpeg a dit en dernier : " + tail(handle));
        } else {
            record.finished(now, size);
        }
        store.save(record);
        say.accept(record.describe(now));
        return record;
    }

    /**
     * {@code drawtext} est-il connu de ce binaire ? Une build minimale d'{@code ffmpeg} est compilée
     * <b>sans</b> lui, et s'en apercevoir au démarrage de la capture donnerait un échec obscur au
     * pire moment. La question est posée une fois, et la réponse gardée.
     */
    private void requireDrawtext(Path ffmpeg) {
        Boolean known = drawtextKnown;
        if (known == null) {
            known = asksForDrawtext(ffmpeg);
            drawtextKnown = known;
        }
        if (!Boolean.TRUE.equals(known)) {
            throw new CaptureRefusedException(CaptureRefusedException.NO_WATERMARK,
                    "L'ffmpeg de ce poste ne connaît pas le filtre « drawtext » : je ne peux pas "
                            + "incruster le filigrane, donc je ne capture pas — un enregistrement "
                            + "doit toujours pouvoir dire qui l'a fait.",
                    "Installez une build complète d'ffmpeg (" + LocalTool.ffmpeg().installAdvice(os)
                            + "), ou effacez la copie rapatriée sous " + folder.toolsDir()
                            + " pour que je la reprenne, puis redemandez.");
        }
    }

    private boolean asksForDrawtext(Path ffmpeg) {
        try {
            ProcessRunner.ProcessResult result = processes.run(
                    List.of(ffmpeg.toAbsolutePath().toString(), "-hide_banner", "-filters"), null,
                    FILTERS_TIMEOUT_MS);
            for (String line : result.stdout()) {
                if (mentionsDrawtext(line)) {
                    return true;
                }
            }
            for (String line : result.stderr()) {
                if (mentionsDrawtext(line)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            // On ne sait pas : on refuse. Supposer que le filtre est là ferait démarrer une capture
            // qui mourrait sans filigrane — le seul résultat qu'on ne veut à aucun prix.
            return false;
        }
    }

    private static boolean mentionsDrawtext(String line) {
        return line != null && line.toLowerCase(Locale.ROOT).contains(DRAWTEXT);
    }

    private static long sizeOf(Path video) {
        try {
            return Files.isRegularFile(video) ? Files.size(video) : 0L;
        } catch (IOException e) {
            return 0L;
        }
    }

    private static String tail(ProcessSession.Handle handle) {
        List<String> lines = handle.tail();
        return lines.isEmpty() ? "ffmpeg n'a rien écrit avant de s'arrêter."
                : String.join(System.lineSeparator(), lines);
    }

    /** 16 caractères hexadécimaux : assez pour ne pas se répéter, assez court pour se citer. */
    private static String newId() {
        byte[] material = new byte[8];
        new Random().nextBytes(material);
        return HexFormat.of().formatHex(material);
    }

    /** Une capture vivante : son état, et le processus qui l'écrit. */
    private record Live(CaptureRecord record, ProcessSession.Handle handle) {
    }

    /**
     * <b>Le témoin au premier plan</b> (SF-91-02), vu par le moteur.
     *
     * <p>Ce moteur ne sait pas dessiner une fenêtre, et il n'a pas à le savoir. Il sait <b>quand</b>
     * une capture commence et finit — c'est la seule chose dont le témoin a besoin — et il lui
     * donne de quoi <b>l'arrêter d'un clic</b>.</p>
     */
    public interface Witness {

        /**
         * Peut-on montrer un témoin sur ce poste ? Demandé <b>avant</b> qu'on lance quoi que ce
         * soit : un refus ne doit rien laisser derrière lui.
         *
         * @throws CaptureWitnessException quand il n'y a pas d'environnement graphique
         */
        void requireAvailable();

        /**
         * Montre le témoin pour cette capture.
         *
         * @param record la capture qui vient de démarrer
         * @param stop   ce qu'il faut appeler quand l'utilisateur clique « arrêter »
         * @return <b>ce qui n'a pas pu être fait</b> — par exemple un système qui refuse le premier
         *         plan : on montre quand même, et on le nomme
         */
        List<TeamsGap> show(CaptureRecord record, Runnable stop);

        /** Efface le témoin. */
        void hide();

        /**
         * <b>Aucun témoin.</b> Ce n'est pas un mode dégradé du produit : c'est ce qu'utilisent les
         * tests, et ce dont un runner se sert avant qu'on lui branche le vrai. Un runner réel qui
         * garderait celui-ci capturerait sans garde-fou n° 3 — d'où le montage explicite dans
         * {@code ToolStack}.
         */
        static Witness none() {
            return new Witness() {
                @Override
                public void requireAvailable() {
                    // Rien à vérifier.
                }

                @Override
                public List<TeamsGap> show(CaptureRecord record, Runnable stop) {
                    return List.of();
                }

                @Override
                public void hide() {
                    // Rien à effacer.
                }
            };
        }
    }

    /**
     * Ce qu'on demande au moteur.
     *
     * @param purpose              l'usage — <b>jamais deviné</b> : absent ou inconnu vaut refus
     * @param participantsInformed la confirmation d'avoir prévenu ; {@code null} vaut <b>non</b>
     * @param audio                capturer le son (défaut du produit : oui)
     * @param identity             qui enregistre, tel qu'il apparaîtra dans le filigrane
     * @param subject              le sujet de la réunion, pour le compte rendu
     * @param screenDevice         entrée vidéo imposée, ou {@code ""}
     * @param audioDevice          entrée audio imposée, ou {@code ""}
     */
    public record Request(String purpose, Boolean participantsInformed, boolean audio,
            String identity, String subject, String screenDevice, String audioDevice) {

        public Request {
            identity = identity == null ? "" : identity.strip();
            subject = subject == null ? "" : subject.strip();
            screenDevice = screenDevice == null ? "" : screenDevice.strip();
            audioDevice = audioDevice == null ? "" : audioDevice.strip();
        }
    }
}
