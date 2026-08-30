package fr.claudegateway.runner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Outil {@code bash} du runner (F-38 / SF-38-07) : exécute <b>une vraie commande</b> sur la machine
 * de l'utilisateur, diffuse sa sortie ligne à ligne, rend son code de sortie, et se laisse tuer.
 *
 * <p>C'est la brique la plus sensible du lot. Les gardes, dans l'ordre où elles s'appliquent :</p>
 * <ol>
 *   <li><b>Opt-in machine</b> : sans {@code --allow-bash}, l'outil répond {@code unsupported_tool}
 *       et rien n'est exécuté. La capacité n'est alors même pas annoncée à la gateway (contrat §2.1),
 *       qui refuse donc l'appel avant émission — mais le refus qui fait foi est celui d'ici.</li>
 *   <li><b>Une commande à la fois</b> : un sémaphore à un jeton. La boucle tool-use est séquentielle,
 *       la limite ne coûte rien, et elle interdit qu'un enchaînement inattendu lance N processus.</li>
 *   <li><b>Confinement</b> : le {@code cwd} passe par le {@link PathGuard} — relatif, sous la racine,
 *       exclusions comprises. Aucun {@code cwd} n'échappe à la racine {@code --workspace}.</li>
 *   <li><b>Bornes</b> : sortie diffusée plafonnée, délai armé par l'aiguilleur, processus
 *       {@code destroyForcibly} dès que l'appel est abandonné.</li>
 * </ol>
 *
 * <p><b>Threads</b> (piège identifié au cadrage) : {@code stdout} et {@code stderr} sont pompés sur
 * <b>deux threads dédiés</b>. Lire un {@code Process} en bloquant sur le thread du heartbeat ferait
 * passer le runner pour « déconnecté » en pleine exécution. Le thread appelant, lui, ne fait
 * qu'attendre la fin du processus.</p>
 *
 * <p><b>Anti-fuite</b> : aucun message d'erreur ne cite un chemin absolu de la machine.</p>
 */
public final class BashTool {

    /** Longueur maximale d'une ligne de commande acceptée. */
    static final int MAX_COMMAND_CHARS = 8_192;

    /** Taille maximale d'un fragment diffusé (contrat §2.3). */
    static final int MAX_CHUNK_BYTES = 16_384;

    /**
     * Sortie totale diffusée par appel. Au-delà, on cesse d'émettre et on marque {@code truncated} :
     * le processus continue (le tuer changerait son comportement), mais la machine n'inonde pas la
     * socket. La gateway ne garde de toute façon que 131 072 octets (contrat §5).
     */
    static final int MAX_STREAM_BYTES = 262_144;

    private final PathGuard guard;
    private final boolean enabled;
    private final Semaphore slot = new Semaphore(1);

    /**
     * @param guard   confinement des chemins ({@code cwd}) à la racine {@code --workspace}
     * @param enabled exécution autorisée sur cette machine ({@code --allow-bash})
     */
    public BashTool(PathGuard guard, boolean enabled) {
        this.guard = guard;
        this.enabled = enabled;
    }

    /** Vrai si cette machine autorise l'exécution de commandes (capacité {@code bash} annoncée). */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Exécute la commande décrite par {@code input} et diffuse sa sortie via {@code context}.
     * Ne lève jamais : toute erreur devient un {@link ToolOutcome} porteur d'un code du contrat (§4).
     */
    public ToolOutcome run(JsonNode input, ToolContext context) {
        if (!enabled) {
            return ToolOutcome.error("unsupported_tool",
                    "L'exécution de commandes n'est pas activée sur ce runner.");
        }
        String command;
        Path workingDirectory;
        try {
            command = requireCommand(input);
            workingDirectory = resolveWorkingDirectory(input);
        } catch (ToolException e) {
            return ToolOutcome.error(e);
        }
        if (!slot.tryAcquire()) {
            // Pas d'exécution concurrente non bornée : un seul processus à la fois par runner.
            return ToolOutcome.error("denied", "Une commande est déjà en cours sur ce runner.");
        }
        try {
            return execute(command, workingDirectory, context);
        } finally {
            slot.release();
        }
    }

    // ------------------------------------------------------------------ exécution

    private ToolOutcome execute(String command, Path workingDirectory, ToolContext context) {
        ProcessBuilder builder = new ProcessBuilder(shellCommand(command))
                .directory(workingDirectory.toFile());
        builder.redirectErrorStream(false); // stdout et stderr restent distinguables (contrat §2.3)

        Process process;
        try {
            process = builder.start();
        } catch (IOException | RuntimeException e) {
            // Interpréteur absent, droits refusés : la commande n'a jamais tourné.
            return ToolOutcome.error("exec_failed", "La commande n'a pas pu être démarrée.");
        }

        StreamBudget budget = new StreamBudget(context);
        // stdin fermé tout de suite : une commande qui lit l'entrée standard reçoit EOF au lieu de
        // pendre jusqu'au délai — un `cat` sans argument ne doit pas bloquer le tour.
        closeQuietly(process.getOutputStream());
        Thread out = pump(process.getInputStream(), "stdout", budget, "runner-bash-out");
        Thread err = pump(process.getErrorStream(), "stderr", budget, "runner-bash-err");

        boolean cancelled = false;
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            // Annulation ou délai dépassé : l'aiguilleur a interrompu ce thread. On tue le processus
            // — sinon la commande continuerait sur la machine après la fin de l'appel.
            Thread.currentThread().interrupt();
            cancelled = true;
            exitCode = -1;
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                waitQuietly(process);
            }
            join(out);
            join(err);
        }

        if (cancelled) {
            // Le code réel (timeout vs cancelled) est posé par l'aiguilleur, qui sait pourquoi il a
            // interrompu ; ce résultat-ci est ignoré si une trame terminale est déjà partie.
            return ToolOutcome.error("cancelled", "Commande interrompue.");
        }
        return new ToolOutcome(true, "", budget.truncated(), budget.bytes(), null, null, exitCode);
    }

    /** Ligne de commande passée à l'interpréteur du système, sans découpage maison des arguments. */
    static List<String> shellCommand(String command) {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return os.contains("win")
                ? List.of("cmd.exe", "/c", command)
                : List.of("/bin/sh", "-c", command);
    }

    /**
     * Thread de pompage d'un flux du processus. Découpe sur les fins de ligne quand c'est possible,
     * et coupe de force au-delà de {@link #MAX_CHUNK_BYTES} pour une sortie sans retour à la ligne.
     */
    private Thread pump(InputStream stream, String name, StreamBudget budget, String threadName) {
        Thread thread = new Thread(() -> {
            StringBuilder line = new StringBuilder();
            byte[] buffer = new byte[8_192];
            // Le décodage est fait sur le tampon accumulé : un caractère UTF-8 à cheval sur deux
            // lectures ne doit pas produire de remplacement.
            try (InputStream in = stream) {
                Utf8Accumulator utf8 = new Utf8Accumulator();
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    for (char c : utf8.decode(buffer, read).toCharArray()) {
                        line.append(c);
                        if (c == '\n' || line.length() >= MAX_CHUNK_BYTES / 4) {
                            budget.emit(name, line.toString());
                            line.setLength(0);
                        }
                    }
                    if (budget.exhausted()) {
                        // Plafond atteint : on continue de vider le flux (sinon le processus se
                        // bloquerait sur un tube plein) mais on n'émet plus rien.
                        line.setLength(0);
                    }
                }
            } catch (IOException | RuntimeException e) {
                // Flux coupé (processus tué) : rien de plus à lire, ce n'est pas une erreur d'outil.
            } finally {
                if (line.length() > 0) {
                    budget.emit(name, line.toString());
                }
            }
        }, threadName);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    // ------------------------------------------------------------------- entrées

    private static String requireCommand(JsonNode input) {
        JsonNode value = input == null ? null : input.get("command");
        if (value == null || !value.isTextual()) {
            throw new ToolException("invalid_input", "Paramètre requis manquant : command");
        }
        String command = value.asText().strip();
        if (command.isEmpty()) {
            throw new ToolException("invalid_input", "Paramètre requis manquant : command");
        }
        if (command.length() > MAX_COMMAND_CHARS) {
            throw new ToolException("invalid_input", "Commande trop longue (8192 caractères au plus).");
        }
        if (command.indexOf('\0') >= 0) {
            throw new ToolException("invalid_input", "Commande invalide.");
        }
        return command;
    }

    /**
     * Répertoire d'exécution : la racine {@code --workspace} par défaut, sinon le {@code cwd} demandé
     * — <b>toujours</b> résolu par le {@link PathGuard}, donc relatif, confiné, et refusé s'il est
     * exclu. Une commande ne s'exécute jamais hors de la racine exposée.
     */
    private Path resolveWorkingDirectory(JsonNode input) {
        JsonNode value = input == null ? null : input.get("cwd");
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return guard.root();
        }
        PathGuard.Resolved resolved = guard.resolve(value.asText());
        if (!Files.exists(resolved.path())) {
            throw new ToolException("not_found", "Dossier introuvable : " + resolved.relative());
        }
        if (!Files.isDirectory(resolved.path())) {
            throw new ToolException("not_a_file", "Le chemin n'est pas un dossier : " + resolved.relative());
        }
        return resolved.path();
    }

    // -------------------------------------------------------------------- outils

    private static void closeQuietly(java.io.OutputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            // Processus déjà terminé : sans conséquence.
        }
    }

    private static void waitQuietly(Process process) {
        try {
            process.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void join(Thread thread) {
        try {
            // Borne courte : les pompes se terminent avec les flux du processus, déjà tué au besoin.
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Budget de diffusion d'un appel : compteur {@code seq} <b>partagé</b> entre {@code stdout} et
     * {@code stderr} (l'ordre des {@code seq} est l'ordre réel, c'est ce qui permet à la gateway de
     * reconstituer l'entrelacement) et plafond d'octets diffusés.
     */
    static final class StreamBudget {
        private final ToolContext context;
        private final AtomicInteger emitted = new AtomicInteger();
        private final AtomicBoolean truncated = new AtomicBoolean(false);
        private long bytes;

        StreamBudget(ToolContext context) {
            this.context = context;
        }

        /** Diffuse un fragment si le plafond n'est pas atteint et si l'appel n'est pas abandonné. */
        synchronized void emit(String stream, String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return;
            }
            long size = chunk.getBytes(StandardCharsets.UTF_8).length;
            if (bytes + size > MAX_STREAM_BYTES) {
                truncated.set(true);
                return;
            }
            bytes += size;
            emitted.incrementAndGet();
            if (!context.cancelled()) {
                context.stream(stream, chunk);
            }
        }

        synchronized boolean exhausted() {
            return truncated.get();
        }

        boolean truncated() {
            return truncated.get();
        }

        synchronized long bytes() {
            return bytes;
        }
    }

    /** Décodeur UTF-8 incrémental : un caractère à cheval sur deux lectures reste intact. */
    private static final class Utf8Accumulator {
        private final java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
        private java.nio.ByteBuffer pending = java.nio.ByteBuffer.allocate(0);

        String decode(byte[] data, int length) {
            java.nio.ByteBuffer input = java.nio.ByteBuffer.allocate(pending.remaining() + length);
            input.put(pending);
            input.put(data, 0, length);
            input.flip();
            java.nio.CharBuffer out = java.nio.CharBuffer.allocate(input.remaining() + 1);
            decoder.decode(input, out, false);
            pending = java.nio.ByteBuffer.allocate(input.remaining());
            pending.put(input);
            pending.flip();
            out.flip();
            return out.toString();
        }
    }
}
