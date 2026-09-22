package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.ToolOutcome;

/**
 * <b>Recevoir un enregistrement déposé depuis l'écran</b> (F-104 / SF-104-04, cadrage §9) : la gateway relaie
 * le fichier <b>par morceaux</b>, le runner l'écrit dans le dossier de dépôt du Radar avec son titre et sa date.
 *
 * <p><b>L'état vit ici, pas dans la gateway.</b> Un dépôt en cours, c'est deux fichiers cachés du dossier :
 * {@code .upload-<id>.part} (les octets reçus) et {@code .upload-<id>.json} (nom, taille annoncée, titre, date).
 * Le collecteur de SF-100-05 ignore les fichiers cachés : un dépôt inachevé n'est jamais transcrit. N'importe quel
 * pod de la gateway peut relayer n'importe quel morceau.</p>
 *
 * <p><b>Reprise sûre.</b> Un morceau porte son {@code offset} : il doit être égal à la taille déjà reçue. Un
 * morceau déjà reçu (même offset, même longueur, dans les bornes) est accepté sans être réécrit — une nouvelle
 * tentative après un échec réseau ne duplique rien.</p>
 *
 * <p><b>À la fin</b>, le compagnon {@code <nom>.json} (forme de SF-100-05) est écrit, puis le fichier est renommé
 * dans le dossier : il apparaît complet, jamais à moitié.</p>
 */
final class RadarDepositReceiver {

    /** Taille maximale d'un enregistrement déposé depuis l'écran. */
    static final long MAX_BYTES = 500L * 1024 * 1024;
    /** Taille maximale d'un morceau, avant encodage. */
    static final int MAX_CHUNK_BYTES = 512 * 1024;
    /** Marge de place disque exigée au-delà de la taille annoncée. */
    static final long DISK_MARGIN = 64L * 1024 * 1024;
    /** Âge au-delà duquel un dépôt inachevé est purgé. */
    static final Duration STALE_AFTER = Duration.ofHours(24);
    /** Attente maximale de la fin d'une transcription avant d'abandonner la remontée (F-147 / SF-147-02). */
    static final Duration MAX_WAIT = Duration.ofHours(6);
    /** Intervalle entre deux regards sur l'avancement du travail. */
    static final long POLL_MS = 1_000L;

    private static final Pattern UPLOAD_ID = Pattern.compile("[0-9a-fA-F-]{36}");
    private static final Pattern FORBIDDEN = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]");

    /** Place disque disponible, remplaçable en test. */
    interface DiskSpace {
        long usable(Path dir) throws IOException;
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path depot;
    private final Supplier<Instant> clock;
    private final DiskSpace disk;
    /**
     * Ce qui transcrit, <b>tout de suite</b> (F-147 / SF-147-01).
     *
     * <p>Avant, {@code finish} posait le fichier et s'arrêtait là : un relevé périodique le prenait,
     * plus tard, sans qu'on sache quand. Le moteur savait pourtant déjà transcrire un fichier
     * désigné — il n'était simplement pas branché sur le dépôt.</p>
     *
     * <p><b>Nul est permis</b> : un récepteur monté sans moteur se comporte comme avant, et le dit.</p>
     */
    private final Transcriber transcriber;

    /** Les travaux lancés par ce récepteur, par identifiant de dépôt. */
    private final java.util.Map<String, TranscriptionJob> jobs = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Ce qui fait monter le texte vers la <b>réunion</b> du dépôt (F-147 / SF-147-02), et ce qui attend
     * la fin du travail sans bloquer l'appel en cours. Nuls sont permis : un récepteur monté sans eux
     * dépose comme avant, et le texte sera relevé plus tard.
     */
    private final TranscriptSink sink;
    private final Watcher watcher;

    /** La réunion à remplir, par identifiant de dépôt — annoncée par la gateway après {@code finish}. */
    private final java.util.Map<String, String> meetings = new java.util.concurrent.ConcurrentHashMap<>();

    /** Ce que la transcription locale offre au dépôt — une interface, pour s'éprouver sans moteur. */
    interface Transcriber {

        /** Lance ou reprend la transcription d'un fichier ; rend le travail. */
        TranscriptionJob start(String id, Path file, Instant startedAt);
    }

    /** Ce qui porte le texte (ou l'échec) jusqu'à la réunion, côté gateway. */
    interface TranscriptSink {

        /** Dépose le résultat d'un travail terminé ; rend vrai si la gateway l'a pris. */
        boolean deposit(String meetingId, TranscriptionJob job);
    }

    /** Ce qui exécute l'attente de la fin d'un travail — ailleurs que dans le fil de l'appel. */
    interface Watcher {

        void watch(Runnable task);
    }

    RadarDepositReceiver(Path depot, Supplier<Instant> clock, DiskSpace disk) {
        this(depot, clock, disk, null);
    }

    RadarDepositReceiver(Path depot, Supplier<Instant> clock, DiskSpace disk, Transcriber transcriber) {
        this(depot, clock, disk, transcriber, null, null);
    }

    RadarDepositReceiver(Path depot, Supplier<Instant> clock, DiskSpace disk, Transcriber transcriber,
            TranscriptSink sink, Watcher watcher) {
        this.depot = depot;
        this.clock = clock;
        this.disk = disk;
        this.transcriber = transcriber;
        this.sink = sink;
        this.watcher = watcher;
    }

    static RadarDepositReceiver real(Path depot) {
        return new RadarDepositReceiver(depot, Instant::now, dir -> Files.getFileStore(dir).getUsableSpace());
    }

    /** Le même, doté du moteur de transcription locale (F-147 / SF-147-01). */
    static RadarDepositReceiver real(Path depot, Transcriber transcriber) {
        return new RadarDepositReceiver(depot, Instant::now,
                dir -> Files.getFileStore(dir).getUsableSpace(), transcriber);
    }

    /** Le même, sachant aussi porter le texte jusqu'à la réunion du dépôt (F-147 / SF-147-02). */
    static RadarDepositReceiver real(Path depot, Transcriber transcriber, TranscriptSink sink, Watcher watcher) {
        return new RadarDepositReceiver(depot, Instant::now,
                dir -> Files.getFileStore(dir).getUsableSpace(), transcriber, sink, watcher);
    }

    /**
     * Point d'entrée : {@code op} = {@code open}, {@code chunk}, {@code finish}, {@code abort},
     * {@code status} (F-147 / SF-147-01 — où en est la transcription) ou {@code attach}
     * (F-147 / SF-147-02 — la réunion où déposer le texte).
     */
    ToolOutcome handle(JsonNode input) {
        if (depot == null) {
            return refused("DEPOT_UNAVAILABLE", "Le dossier de dépôt du Radar n'existe pas sur ce poste.");
        }
        String op = text(input, "op");
        String id = text(input, "upload_id");
        if (!UPLOAD_ID.matcher(id).matches()) {
            return ToolOutcome.error("invalid_input", "Identifiant de dépôt illisible.");
        }
        try {
            return switch (op) {
                case "open" -> open(input, id);
                case "chunk" -> chunk(input, id);
                case "finish" -> finish(id);
                case "abort" -> abort(id);
                case "status" -> status(id);
                case "attach" -> attach(input, id);
                default -> ToolOutcome.error("invalid_input", "Opération de dépôt inconnue : " + op + ".");
            };
        } catch (IOException e) {
            return ToolOutcome.error("io_error", "Écriture impossible dans le dossier de dépôt du Radar.");
        }
    }

    // ------------------------------------------------------------------------------------ opérations

    private ToolOutcome open(JsonNode input, String id) throws IOException {
        String fileName = cleanName(text(input, "file_name"));
        long size = input == null ? 0 : input.path("size").asLong(0);
        String title = text(input, "title");
        String date = text(input, "recorded_at");
        if (fileName.isEmpty() || extensionOf(fileName) == null) {
            return ToolOutcome.error("invalid_input", "Un enregistrement est un fichier audio ou vidéo ("
                    + String.join(" ", RadarDepositCollector.EXTENSIONS) + ").");
        }
        if (size <= 0 || size > MAX_BYTES) {
            return ToolOutcome.error("invalid_input", "Un enregistrement déposé depuis l'écran pèse au plus 500 Mio.");
        }
        if (title.isEmpty() || date.isEmpty()) {
            return ToolOutcome.error("invalid_input", "Le titre et la date de l'enregistrement sont requis.");
        }
        Files.createDirectories(depot);
        purgeStale();
        if (disk.usable(depot) < size + DISK_MARGIN) {
            return refused("DISK_FULL", "Place insuffisante sur le poste pour déposer cet enregistrement.");
        }
        ObjectNode meta = mapper.createObjectNode();
        meta.put("file_name", fileName);
        meta.put("size", size);
        meta.put("title", title.length() <= 200 ? title : title.substring(0, 200));
        meta.put("recorded_at", date);
        // F-147 / SF-147-02 : le sujet choisi au geste voyage avec le dépôt. Le poste ne le juge pas —
        // il le rend à la fin, et c'est la gateway qui le revalide dans son périmètre.
        String subject = text(input, "subject_id");
        if (!subject.isEmpty()) {
            meta.put("subject_id", subject);
        }
        Files.writeString(metaOf(id), meta.toString());
        Files.write(partOf(id), new byte[0], StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return accepted(0, size);
    }

    private ToolOutcome chunk(JsonNode input, String id) throws IOException {
        JsonNode meta = meta(id);
        if (meta == null) {
            return unknown();
        }
        long size = meta.path("size").asLong();
        long offset = input == null ? -1 : input.path("offset").asLong(-1);
        byte[] data;
        try {
            data = Base64.getDecoder().decode(text(input, "data"));
        } catch (IllegalArgumentException e) {
            return ToolOutcome.error("invalid_input", "Morceau illisible.");
        }
        if (data.length == 0 || data.length > MAX_CHUNK_BYTES) {
            return ToolOutcome.error("invalid_input", "Un morceau pèse au plus 512 Kio.");
        }
        long received = Files.size(partOf(id));
        if (offset + data.length <= received && offset >= 0 && offset + data.length <= size) {
            // Morceau déjà reçu (nouvelle tentative) : rien n'est réécrit.
            return accepted(received, size);
        }
        if (offset != received) {
            return refused("OFFSET_MISMATCH", "Le poste a reçu " + received + " octet(s) : reprenez à cet endroit.");
        }
        if (received + data.length > size) {
            return refused("SIZE_EXCEEDED", "Le fichier dépasse la taille annoncée.");
        }
        try (OutputStream out = Files.newOutputStream(partOf(id), StandardOpenOption.APPEND)) {
            out.write(data);
        }
        return accepted(received + data.length, size);
    }

    private ToolOutcome finish(String id) throws IOException {
        JsonNode meta = meta(id);
        if (meta == null) {
            return unknown();
        }
        long size = meta.path("size").asLong();
        long received = Files.size(partOf(id));
        if (received != size) {
            return refused("INCOMPLETE", "Le poste a reçu " + received + " octet(s) sur " + size + " : dépôt incomplet.");
        }
        String fileName = meta.path("file_name").asText();
        String extension = extensionOf(fileName);
        String base = fileName.substring(0, fileName.length() - extension.length());
        Path target = depot.resolve(fileName);
        for (int n = 2; Files.exists(target) || Files.exists(depot.resolve(nameOf(target, extension) + ".json")); n++) {
            target = depot.resolve(base + " (" + n + ")" + extension);
        }
        String finalBase = nameOf(target, extension);
        ObjectNode companion = mapper.createObjectNode();
        companion.put("title", meta.path("title").asText());
        companion.put("date", meta.path("recorded_at").asText());
        Files.writeString(depot.resolve(finalBase + ".json"), companion.toString());
        Files.move(partOf(id), target, StandardCopyOption.ATOMIC_MOVE);
        Files.deleteIfExists(metaOf(id));
        ObjectNode done = mapper.createObjectNode();
        done.put("deposited", true);
        done.put("file_name", target.getFileName().toString());
        done.put("size", size);
        done.put("title", meta.path("title").asText());
        done.put("recorded_at", meta.path("recorded_at").asText());
        if (meta.hasNonNull("subject_id")) {
            done.put("subject_id", meta.path("subject_id").asText());
        }
        // F-147 / SF-147-01 : on transcrit TOUT DE SUITE. Le fichier est là, le moteur est là ;
        // attendre un relevé périodique n'apportait qu'un délai que personne ne pouvait prévoir.
        startTranscription(id, target, done);
        return ToolOutcome.ok(done.toString());
    }

    /**
     * Lance la transcription du fichier qui vient d'arriver, et dit son état dans la réponse.
     *
     * <p><b>Ne fait jamais échouer le dépôt</b> : le fichier est arrivé entier, c'est acquis. Si le
     * moteur manque ou refuse, on le <b>dit</b> et le dépôt reste bon — un relevé ultérieur ou une
     * reprise pourra le traiter.</p>
     */
    private void startTranscription(String id, Path target, ObjectNode done) {
        if (transcriber == null) {
            done.put("transcription", "unavailable");
            return;
        }
        try {
            TranscriptionJob job = transcriber.start(id, target, clock.get());
            jobs.put(id, job);
            done.put("transcription", "started");
            done.put("job_id", job.id());
            done.put("phase", job.phase().name());
            done.put("phase_label", job.phase().label());
        } catch (RuntimeException e) {
            done.put("transcription", "refused");
            done.put("transcription_error", e.getMessage() == null ? "" : e.getMessage());
        }
    }

    /**
     * Où en est la transcription de ce dépôt (F-147 / SF-147-01).
     *
     * <p>La <b>phase en toutes lettres</b> voyage avec : elle a été écrite pour être lue par
     * l'utilisateur — « j'extrais le son », « je transcris, ici, sans rien envoyer nulle part » —
     * et la recopier côté écran en ferait une seconde version à maintenir.</p>
     */
    private ToolOutcome status(String id) {
        TranscriptionJob job = jobs.get(id);
        ObjectNode node = mapper.createObjectNode();
        if (job == null) {
            node.put("known", false);
            return ToolOutcome.ok(node.toString());
        }
        node.put("known", true);
        node.put("job_id", job.id());
        node.put("phase", job.phase().name());
        node.put("phase_label", job.phase().label());
        node.put("over", job.isOver());
        String failure = job.failure();
        if (failure != null && !failure.isBlank()) {
            node.put("failure", failure);
        }
        return ToolOutcome.ok(node.toString());
    }

    /**
     * <b>La réunion où déposer le texte</b> (F-147 / SF-147-02) : la gateway l'annonce juste après
     * {@code finish}, une fois le fichier arrivé — une réunion créée plus tôt resterait fantôme si le
     * transfert était abandonné.
     *
     * <p>Le poste <b>attend la fin du travail</b> puis poste le texte ; il n'attend rien dans le fil de
     * cet appel, qui répond tout de suite. Sans moyen de faire monter le texte ({@code sink} nul), on le
     * <b>dit</b> : le fichier reste sur la machine et un relevé ultérieur le reprendra.</p>
     */
    private ToolOutcome attach(JsonNode input, String id) {
        String meetingId = text(input, "meeting_id");
        ObjectNode node = mapper.createObjectNode();
        if (meetingId.isEmpty()) {
            return ToolOutcome.error("invalid_input", "Identifiant de réunion manquant.");
        }
        meetings.put(id, meetingId);
        TranscriptionJob job = jobs.get(id);
        if (job == null) {
            node.put("attached", false);
            node.put("reason", "NO_JOB");
            return ToolOutcome.ok(node.toString());
        }
        if (sink == null || watcher == null) {
            node.put("attached", false);
            node.put("reason", "NO_UPLINK");
            return ToolOutcome.ok(node.toString());
        }
        watcher.watch(() -> deliver(id, meetingId, job));
        node.put("attached", true);
        node.put("job_id", job.id());
        return ToolOutcome.ok(node.toString());
    }

    /**
     * Attend la fin du travail, puis porte le résultat à la réunion — le texte s'il y en a, l'échec
     * sinon. <b>L'attente est bornée</b> : une transcription qui ne finit jamais ne doit pas laisser
     * un fil vivant pour l'éternité.
     */
    private void deliver(String id, String meetingId, TranscriptionJob job) {
        long deadline = System.nanoTime() + MAX_WAIT.toNanos();
        while (!job.isOver() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(POLL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        try {
            sink.deposit(meetingId, job);
        } catch (RuntimeException e) {
            // La réunion reste « en attente » : le texte n'est pas perdu, il est resté ici.
            meetings.remove(id);
        }
    }

    private ToolOutcome abort(String id) throws IOException {
        meetings.remove(id);
        Files.deleteIfExists(partOf(id));
        Files.deleteIfExists(metaOf(id));
        ObjectNode done = mapper.createObjectNode();
        done.put("aborted", true);
        return ToolOutcome.ok(done.toString());
    }

    // ------------------------------------------------------------------------------------ aides

    /** Purge les dépôts inachevés trop anciens. */
    void purgeStale() throws IOException {
        Instant limit = clock.get().minus(STALE_AFTER);
        try (Stream<Path> listing = Files.list(depot)) {
            for (Path path : listing.toList()) {
                String name = path.getFileName().toString();
                if (name.startsWith(".upload-") && Files.getLastModifiedTime(path).toInstant().isBefore(limit)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    /** Le nom seul, sans chemin ni caractère interdit, 120 caractères au plus (extension gardée). */
    static String cleanName(String raw) {
        String value = raw == null ? "" : raw.strip();
        int cut = Math.max(value.lastIndexOf('/'), value.lastIndexOf('\\'));
        value = FORBIDDEN.matcher(cut >= 0 ? value.substring(cut + 1) : value).replaceAll("_").strip();
        while (value.startsWith(".")) {
            value = value.substring(1);
        }
        String extension = extensionOf(value);
        if (extension != null && value.length() > 120) {
            value = value.substring(0, 120 - extension.length()).strip() + extension;
        }
        return value;
    }

    private static String extensionOf(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return RadarDepositCollector.EXTENSIONS.stream()
                .filter(lower::endsWith)
                .filter(ext -> name.length() > ext.length())
                .map(ext -> name.substring(name.length() - ext.length()))
                .findFirst().orElse(null);
    }

    private static String nameOf(Path target, String extension) {
        String name = target.getFileName().toString();
        return name.substring(0, name.length() - extension.length());
    }

    private JsonNode meta(String id) throws IOException {
        Path meta = metaOf(id);
        if (!Files.isRegularFile(meta) || !Files.isRegularFile(partOf(id))) {
            return null;
        }
        return mapper.readTree(Files.readString(meta));
    }

    private Path partOf(String id) {
        return depot.resolve(".upload-" + id.toLowerCase(Locale.ROOT) + ".part");
    }

    private Path metaOf(String id) {
        return depot.resolve(".upload-" + id.toLowerCase(Locale.ROOT) + ".json");
    }

    private ToolOutcome accepted(long received, long size) {
        ObjectNode node = mapper.createObjectNode();
        node.put("accepted", true);
        node.put("received", received);
        node.put("size", size);
        return ToolOutcome.ok(node.toString());
    }

    private ToolOutcome refused(String reason, String sentence) {
        ObjectNode node = mapper.createObjectNode();
        node.put("accepted", false);
        node.put("reason", reason);
        node.put("sentence", sentence);
        return ToolOutcome.ok(node.toString());
    }

    private ToolOutcome unknown() {
        return refused("UNKNOWN_UPLOAD", "Ce dépôt n'existe pas sur le poste (abandonné ou purgé) : recommencez.");
    }

    private static String text(JsonNode input, String field) {
        return input == null ? "" : input.path(field).asText("").strip();
    }
}
