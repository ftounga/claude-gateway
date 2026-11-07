package fr.claudegateway.runner.mail;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;

import fr.claudegateway.runner.RunnerActivity;
import fr.claudegateway.runner.RunnerHeaders;
import fr.claudegateway.runner.RunnerHttp;

/**
 * <b>Le dossier des mails</b> (F-249 / SF-249-02) — le PO glisse ses mails Outlook ({@code .msg}, {@code .eml}) dans
 * {@code <racine>/jarvis/mails/} ; ce fil, de <b>priorité minimale</b>, les envoie <b>un par un et une seule fois</b> à
 * la passerelle ({@code POST /runner/mail-deposit}), puis les range dans {@code envoyes/AAAA-MM/} (ou
 * {@code refuses/}). Le rangement est le curseur ; un fichier verrouillé est retenu par son empreinte
 * ({@code .envoyes}) pour n'être jamais renvoyé.
 *
 * <p><b>Jamais pendant un tour</b> : un passage est sauté dès que le runner a une activité ({@link RunnerActivity}), et
 * s'interrompt entre deux fichiers si une activité apparaît.</p>
 */
public final class MailDepositWatcher {

    /** Capacité annoncée à la gateway : ce poste sait déposer des mails. */
    public static final String CAPABILITY = "mail_deposit";
    /** L'activité de ce fil, exclue de son propre test d'occupation. */
    public static final String ACTIVITY = "courrier";

    static final List<String> EXTENSIONS = List.of(".msg", ".eml");
    static final Duration STABLE_AFTER = Duration.ofMinutes(1);
    static final Duration FIRST_PASS = Duration.ofSeconds(30);
    static final Duration EVERY = Duration.ofMinutes(5);
    static final int MAX_PER_PASS = 20;
    static final long MAX_BYTES = 20L * 1024 * 1024;
    static final int SENT_MEMORY = 500;
    static final String SENT_FILE = ".envoyes";
    static final String README = "LISEZ-MOI.txt";
    static final String SENT_DIR = "envoyes";
    static final String REFUSED_DIR = "refuses";
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    /** La réponse de la passerelle à un envoi. */
    public record Response(int status) {
    }

    /** L'envoi d'un fichier (interface : s'éprouve sans réseau). */
    @FunctionalInterface
    public interface Uploader {
        Response upload(String fileName, byte[] content) throws IOException;
    }

    /** Le bilan d'un passage. */
    public record Report(boolean skipped, int sent, int refused, int deferred, boolean stopped) {
    }

    private final Path dir;
    private final Uploader uploader;
    private final Supplier<Instant> clock;
    private final BooleanSupplier busy;
    private final Consumer<String> say;

    public MailDepositWatcher(Path hostRoot, Uploader uploader, Supplier<Instant> clock, BooleanSupplier busy,
            Consumer<String> say) {
        this.dir = hostRoot.resolve("jarvis").resolve("mails");
        this.uploader = uploader;
        this.clock = clock == null ? Instant::now : clock;
        this.busy = busy == null ? MailDepositWatcher::othersBusy : busy;
        this.say = say == null ? s -> { } : say;
    }

    /** Le montage réel : client HTTP du runner, jeton relu à chaque envoi. */
    public static MailDepositWatcher forRuntime(Path hostRoot, String depositUrl, Supplier<String> token,
            Consumer<String> say) {
        return new MailDepositWatcher(hostRoot, http(RunnerHttp.client(), depositUrl, token), Instant::now, null, say);
    }

    /** L'envoi HTTP : corps brut, jeton et nom de fichier en en-têtes. */
    public static Uploader http(HttpClient client, String url, Supplier<String> token) {
        return (name, content) -> {
            String t = token == null ? null : token.get();
            if (t == null || t.isBlank()) {
                return new Response(401);
            }
            try {
                HttpResponse<Void> r = client.send(HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(60)).header(RunnerHeaders.TOKEN, t)
                        .header("X-File-Name", neutralName(name))
                        .header("Content-Type", "application/octet-stream")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(content)).build(),
                        HttpResponse.BodyHandlers.discarding());
                return new Response(r.statusCode());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrompu", e);
            }
        };
    }

    /**
     * Le nom envoyé : seulement l'extension ({@code mail.msg}) — le nom d'un fichier glissé depuis Outlook est l'objet
     * du mail, qui n'a rien à faire dans un en-tête.
     */
    static String neutralName(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".eml") ? "mail.eml" : "mail.msg";
    }

    /** Le dossier surveillé. */
    public Path dir() {
        return dir;
    }

    /** Crée le dossier et son mode d'emploi ; faux si la racine n'est pas inscriptible. */
    public boolean prepare() {
        try {
            Files.createDirectories(dir);
            Path readme = dir.resolve(README);
            if (!Files.exists(readme)) {
                Files.writeString(readme, """
                        Mails Outlook de ce client pour Jarvis (F-249)
                        ==============================================

                        Glissez ici vos mails depuis Outlook (fichiers .msg) ou des fichiers .eml.
                        Le runner les envoie en arrière-plan (toutes les 5 minutes, jamais pendant un
                        travail en cours), une seule fois chacun, puis les range :
                          - envoyes/AAAA-MM/ : reçus par Jarvis ;
                          - refuses/        : format illisible ou fichier de plus de 20 Mo.
                        Un mail déjà envoyé n'est jamais retraité, même redéposé.
                        """, StandardCharsets.UTF_8);
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Lance le fil : premier passage à 30 s, puis toutes les 5 min. */
    public ScheduledExecutorService start() {
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "runner-mail-deposit");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
        executor.scheduleWithFixedDelay(() -> {
            try {
                Report report = runOnce();
                if (report.sent() > 0 || report.refused() > 0) {
                    say.accept("Mails : " + report.sent() + " envoyé(s) à Jarvis"
                            + (report.refused() > 0 ? ", " + report.refused() + " refusé(s) (voir refuses/)" : "")
                            + ".");
                }
            } catch (RuntimeException e) {
                // un passage raté ne casse ni le fil ni le runner : le suivant reprendra
            }
        }, FIRST_PASS.toSeconds(), EVERY.toSeconds(), TimeUnit.SECONDS);
        return executor;
    }

    /** Un passage. */
    public Report runOnce() {
        if (busy.getAsBoolean()) {
            return new Report(true, 0, 0, 0, false);
        }
        List<Path> files;
        try {
            files = candidates();
        } catch (IOException e) {
            return new Report(false, 0, 0, 0, false);
        }
        Set<String> sent = readSent();
        int sentCount = 0;
        int refused = 0;
        int deferred = Math.max(0, files.size() - MAX_PER_PASS);
        boolean stopped = false;
        try (RunnerActivity.Scope ignored = RunnerActivity.begin(ACTIVITY)) {
            for (Path file : files.subList(0, Math.min(files.size(), MAX_PER_PASS))) {
                if (busy.getAsBoolean()) {
                    stopped = true;
                    break;
                }
                try {
                    if (Files.size(file) > MAX_BYTES) {
                        move(file, dir.resolve(REFUSED_DIR));
                        refused++;
                        continue;
                    }
                    byte[] content = Files.readAllBytes(file);
                    String hash = sha256(content);
                    if (sent.contains(hash)) {
                        tryMove(file, monthDir()); // déjà reçu : on retente seulement le rangement
                        continue;
                    }
                    int status = uploader.upload(file.getFileName().toString(), content).status();
                    if (status == 200) {
                        sentCount++;
                        if (!tryMove(file, monthDir())) {
                            sent.add(hash);
                            writeSent(sent);
                        }
                    } else if (status == 400 || status == 413 || status == 415 || status == 422) {
                        refused++;
                        tryMove(file, dir.resolve(REFUSED_DIR));
                    } else {
                        stopped = true; // jeton, passerelle ancienne ou indisponible : on reviendra
                        break;
                    }
                } catch (IOException e) {
                    stopped = true;
                    break;
                }
            }
        }
        return new Report(false, sentCount, refused, deferred, stopped);
    }

    /** Les fichiers à envoyer : premier niveau, extension connue, non vides, stables, du plus ancien au plus récent. */
    List<Path> candidates() throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        Instant stable = clock.get().minus(STABLE_AFTER);
        List<Path> out = new ArrayList<>();
        try (Stream<Path> listing = Files.list(dir)) {
            for (Path p : listing.toList()) {
                String name = p.getFileName().toString();
                String lower = name.toLowerCase(Locale.ROOT);
                if (name.startsWith(".") || name.startsWith("~") || !Files.isRegularFile(p)
                        || EXTENSIONS.stream().noneMatch(lower::endsWith)) {
                    continue;
                }
                if (Files.size(p) == 0 || Files.getLastModifiedTime(p).toInstant().isAfter(stable)) {
                    continue;
                }
                out.add(p);
            }
        }
        out.sort(Comparator.comparing(p -> {
            try {
                return Files.getLastModifiedTime(p).toInstant();
            } catch (IOException e) {
                return Instant.EPOCH;
            }
        }));
        return out;
    }

    private Path monthDir() {
        return dir.resolve(SENT_DIR).resolve(MONTH.format(clock.get().atZone(ZoneId.systemDefault())));
    }

    private boolean tryMove(Path file, Path target) {
        try {
            move(file, target);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    /** Range sous {@code target} ; un nom déjà pris reçoit « (2) », « (3) »… */
    static Path move(Path file, Path target) throws IOException {
        Files.createDirectories(target);
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        Path dest = target.resolve(name);
        for (int i = 2; Files.exists(dest) && i < 1000; i++) {
            dest = target.resolve(base + " (" + i + ")" + ext);
        }
        return Files.move(file, dest, StandardCopyOption.ATOMIC_MOVE);
    }

    Set<String> readSent() {
        Set<String> out = new LinkedHashSet<>();
        try {
            Path f = dir.resolve(SENT_FILE);
            if (Files.isRegularFile(f)) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    if (line.strip().matches("[0-9a-f]{64}")) {
                        out.add(line.strip());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // mémoire illisible : on repart sans (la passerelle dédoublonne de toute façon)
        }
        return out;
    }

    private void writeSent(Set<String> sent) {
        List<String> all = new ArrayList<>(sent);
        List<String> kept = all.subList(Math.max(0, all.size() - SENT_MEMORY), all.size());
        try {
            Files.write(dir.resolve(SENT_FILE), kept, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            // au pire, un renvoi : la passerelle répondra DUPLICATE
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Une activité du runner autre que ce fil (commande, capture, synchro, téléchargement). */
    static boolean othersBusy() {
        return RunnerActivity.busy().stream().anyMatch(label -> !ACTIVITY.equals(label));
    }
}
