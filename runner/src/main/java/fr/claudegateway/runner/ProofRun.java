package fr.claudegateway.runner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>Les captures de preuve</b> (F-212 / SF-212-01) : l'outil {@code proof_run} exécute 1 à
 * {@value #MAX_COMMANDS} commandes <b>à la suite, dans un seul et même shell</b>, et rend, pour chacune,
 * ce qu'elle a réellement produit : sortie standard, sortie d'erreur, code de sortie, durée, dossier
 * courant, heure de départ.
 *
 * <p><b>Pourquoi un seul shell.</b> {@code bash} lance un processus par commande : un {@code cd} ou une
 * variable posés par la première sont perdus pour la seconde. Une preuve se lit comme une session de
 * terminal ; elle doit en avoir le comportement. Le script assemblé ici place les commandes l'une après
 * l'autre, séparées par des <b>bornes</b> imprimées sur les deux flux. Une borne porte un jeton tiré au
 * hasard à chaque appel : aucune sortie de commande ne peut l'imiter.</p>
 *
 * <p><b>Ce qu'on ne fait pas</b> : aucune inspection de la commande (la porte et l'invite sont à la
 * gateway, exactement comme pour {@code bash}), aucune image (rendue côté gateway, à partir de ces
 * relevés bruts — jamais d'un texte écrit par le modèle).</p>
 *
 * <p>Une commande qui termine le shell ({@code exit 3}) termine la session : elle est relevée
 * « interrompue » avec le code du processus, les suivantes « non exécutées ».</p>
 */
public final class ProofRun {

    /** Nom de l'outil sur le fil. */
    public static final String TOOL = "proof_run";
    /** Capacité annoncée dans la trame {@code ready} : sans elle, la gateway dit « mettez à jour le poste ». */
    public static final String CAPABILITY = "proof";
    /** Commandes au plus par appel. */
    public static final int MAX_COMMANDS = 8;
    /** Caractères gardés en tête de chaque flux d'une commande. */
    static final int HEAD_CHARS = 24 * 1024;
    /** Caractères gardés en fin de chaque flux d'une commande (64 Kio par commande, deux flux). */
    static final int TAIL_CHARS = 8 * 1024;
    /** Une ligne sans fin plus longue que ceci n'est pas une borne : elle est versée telle quelle. */
    private static final int MAX_PENDING_LINE = 16 * 1024;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Statut d'une commande dans la session. */
    enum Status {
        /** Bornes de début et de fin vues : la commande a rendu la main au shell. */
        DONE("done"),
        /** Début vu, fin jamais vue : le shell s'est arrêté (exit, délai, annulation). */
        INTERRUPTED("interrupted"),
        /** Jamais commencée. */
        NOT_RUN("not_run");

        final String wire;

        Status(String wire) {
            this.wire = wire;
        }
    }

    private ProofRun() {
    }

    // ------------------------------------------------------------------ entrées

    /** Les commandes demandées, vérifiées ; lève {@code invalid_input} sinon. */
    static List<String> commands(JsonNode input) {
        JsonNode node = input == null ? null : input.get("commands");
        if (node == null || !node.isArray() || node.isEmpty()) {
            throw new ToolException("invalid_input", "Paramètre requis manquant : commands (1 à "
                    + MAX_COMMANDS + " commandes).");
        }
        if (node.size() > MAX_COMMANDS) {
            throw new ToolException("invalid_input", "Trop de commandes : " + MAX_COMMANDS + " au plus.");
        }
        List<String> commands = new ArrayList<>();
        for (JsonNode value : node) {
            if (value == null || !value.isTextual() || value.asText().isBlank()) {
                throw new ToolException("invalid_input", "Chaque commande doit être un texte non vide.");
            }
            String command = value.asText().strip();
            if (command.length() > BashTool.MAX_COMMAND_CHARS) {
                throw new ToolException("invalid_input", "Commande trop longue (8192 caractères au plus).");
            }
            if (command.indexOf('\0') >= 0) {
                throw new ToolException("invalid_input", "Commande invalide.");
            }
            commands.add(command);
        }
        return commands;
    }

    // ------------------------------------------------------------------ script

    /** Jeton des bornes : neuf à chaque appel, impossible à deviner pour une sortie de commande. */
    static String newNonce() {
        byte[] bytes = new byte[12];
        RANDOM.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    static String marker(String nonce) {
        return "@@CGP-" + nonce + ":";
    }

    /**
     * Le script d'une session, dans la langue du shell élu. Chaque commande est encadrée d'une borne
     * de début (sur les deux flux) et d'une borne de fin portant son code et le dossier courant
     * (sur la sortie standard), précédée d'un saut de ligne pour qu'une sortie sans fin de ligne ne la
     * colle pas — ce saut est retiré au relevé.
     *
     * @return pour {@code cmd.exe}, le contenu d'un fichier {@code .cmd} ; sinon le texte passé au shell
     */
    static String script(ShellElection.Kind kind, String nonce, List<String> commands) {
        String m = marker(nonce);
        StringBuilder script = new StringBuilder();
        String nl = kind == ShellElection.Kind.CMD ? "\r\n" : "\n";
        if (kind == ShellElection.Kind.CMD) {
            script.append("@echo off").append(nl);
        }
        for (int i = 0; i < commands.size(); i++) {
            int n = i + 1;
            String begin = m + "B:" + n;
            String end = m + "E:" + n;
            String command = commands.get(i);
            switch (kind) {
                case POSIX -> script
                        .append("printf '%s\\n' '").append(begin).append("'; printf '%s\\n' '")
                        .append(begin).append("' >&2").append(nl)
                        .append(command).append(nl)
                        .append("__cgp_s=$?").append(nl)
                        .append("printf '\\n%s:%s:%s\\n' '").append(end).append("' \"$__cgp_s\" \"$PWD\"; ")
                        .append("printf '\\n%s\\n' '").append(end).append("' >&2").append(nl);
                case POWERSHELL -> script
                        .append("[Console]::Out.WriteLine('").append(begin).append("'); [Console]::Error.WriteLine('")
                        .append(begin).append("')").append(nl)
                        .append("$global:LASTEXITCODE = 0; $__cgpErrors = $Error.Count").append(nl)
                        // Dot-source : un Set-Location ou une variable posés ici restent pour la suite.
                        // Out-String -Stream : la sortie formatée part AVANT la borne de fin (sans lui,
                        // le formatage en tableau est différé et passerait après).
                        .append("try { . {").append(nl).append(command).append(nl)
                        .append("} | Out-String -Stream | ForEach-Object { [Console]::Out.WriteLine($_) } }")
                        .append(" catch { [Console]::Error.WriteLine($_.ToString()) }").append(nl)
                        .append("$__cgpS = if ($LASTEXITCODE) { $LASTEXITCODE } elseif ($Error.Count -gt $__cgpErrors) { 1 } else { 0 }")
                        .append(nl)
                        .append("[Console]::Out.WriteLine(''); [Console]::Out.WriteLine('").append(end)
                        .append(":' + $__cgpS + ':' + (Get-Location).Path); [Console]::Error.WriteLine(''); ")
                        .append("[Console]::Error.WriteLine('").append(end).append("')").append(nl);
                case CMD -> script
                        .append("echo ").append(begin).append(nl)
                        .append("echo ").append(begin).append(" 1>&2").append(nl)
                        .append(command).append(nl)
                        .append("echo.").append(nl)
                        .append("echo ").append(end).append(":%ERRORLEVEL%:%CD%").append(nl)
                        .append("echo. 1>&2").append(nl)
                        .append("echo ").append(end).append(" 1>&2").append(nl);
            }
        }
        if (kind == ShellElection.Kind.POWERSHELL) {
            script.append("exit 0").append(nl);
        }
        return script.toString();
    }

    // ------------------------------------------------------------------ exécution

    /**
     * Exécute la session et rend le relevé, en JSON, dans le contenu du résultat.
     *
     * @param shell            interpréteur élu au démarrage du runner
     * @param workingDirectory dossier de départ de la session
     * @param host             le poste, tel que l'image doit le montrer, et le shell de la session
     */
    static ToolOutcome execute(ShellElection shell, ProofHost host, Path workingDirectory,
            List<String> commands, ToolContext context) {
        String nonce = newNonce();
        String script = script(shell.kind(), nonce, commands);
        Path scriptFile = null;
        List<String> line;
        try {
            if (shell.kind() == ShellElection.Kind.CMD) {
                scriptFile = Files.createTempFile("cg-proof-", ".cmd");
                Files.writeString(scriptFile, script, StandardCharsets.UTF_8);
                line = shell.commandLine("\"" + scriptFile + "\"");
            } else {
                line = host.commandLine(shell, script);
            }
        } catch (IOException e) {
            return ToolOutcome.error("exec_failed", "La session de preuve n'a pas pu être préparée.");
        }
        Process process;
        try {
            ProcessBuilder builder = new ProcessBuilder(line).directory(workingDirectory.toFile());
            builder.redirectErrorStream(false);
            process = builder.start();
        } catch (IOException | RuntimeException e) {
            deleteQuietly(scriptFile);
            return ToolOutcome.error("exec_failed", "La session de preuve n'a pas pu être démarrée.");
        }
        try {
            closeQuietly(process.getOutputStream());
            Session session = new Session(commands, marker(nonce));
            Thread out = pump(process.getInputStream(), session::stdoutLine, "runner-proof-out");
            Thread err = pump(process.getErrorStream(), session::stderrLine, "runner-proof-err");
            boolean cancelled = false;
            int exitCode;
            try {
                exitCode = process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                cancelled = true;
                exitCode = -1;
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    try {
                        process.waitFor(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                join(out);
                join(err);
            }
            if (cancelled || context.cancelled()) {
                return ToolOutcome.error("cancelled", "Session de preuve interrompue.");
            }
            String content = session.report(shell, host, workingDirectory.toAbsolutePath().normalize().toString(),
                    exitCode);
            return new ToolOutcome(true, content, session.truncated(), content.length(), null, null, null);
        } finally {
            deleteQuietly(scriptFile);
        }
    }

    /** Pompe ligne à ligne (fins de ligne conservées), décodage UTF-8 tolérant aux coupures. */
    private static Thread pump(InputStream stream, Consumer<String> sink, String name) {
        Thread thread = new Thread(() -> {
            java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
            java.nio.ByteBuffer pending = java.nio.ByteBuffer.allocate(0);
            StringBuilder lineBuffer = new StringBuilder();
            byte[] buffer = new byte[8_192];
            try (InputStream in = stream) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    java.nio.ByteBuffer input = java.nio.ByteBuffer.allocate(pending.remaining() + read);
                    input.put(pending).put(buffer, 0, read).flip();
                    java.nio.CharBuffer chars = java.nio.CharBuffer.allocate(input.remaining() + 1);
                    decoder.decode(input, chars, false);
                    pending = java.nio.ByteBuffer.allocate(input.remaining());
                    pending.put(input).flip();
                    chars.flip();
                    while (chars.hasRemaining()) {
                        char c = chars.get();
                        lineBuffer.append(c);
                        if (c == '\n' || lineBuffer.length() >= MAX_PENDING_LINE) {
                            sink.accept(lineBuffer.toString());
                            lineBuffer.setLength(0);
                        }
                    }
                }
            } catch (IOException | RuntimeException e) {
                // Flux coupé (processus tué) : on garde ce qui a été lu.
            } finally {
                if (lineBuffer.length() > 0) {
                    sink.accept(lineBuffer.toString());
                }
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    // ------------------------------------------------------------------ relevé

    /** Relevé d'une session : les deux flux découpés aux bornes, commande par commande. */
    static final class Session {
        private final List<String> commands;
        private final String marker;
        private final Entry[] entries;
        /** Commande en cours sur chaque flux (index 0-based), -1 hors commande. */
        private int stdoutCurrent = -1;
        private int stderrCurrent = -1;

        Session(List<String> commands, String marker) {
            this.commands = commands;
            this.marker = marker;
            this.entries = new Entry[commands.size()];
            for (int i = 0; i < entries.length; i++) {
                entries[i] = new Entry();
            }
        }

        synchronized void stdoutLine(String line) {
            String bare = strip(line);
            if (bare.startsWith(marker)) {
                String[] parts = bare.substring(marker.length()).split(":", 4);
                int index = index(parts);
                if (index < 0) {
                    return;
                }
                Entry entry = entries[index];
                if ("B".equals(parts[0])) {
                    entry.begun = true;
                    entry.startNanos = System.nanoTime();
                    entry.startedAt = Instant.now();
                    stdoutCurrent = index;
                } else if ("E".equals(parts[0])) {
                    entry.ended = true;
                    entry.endNanos = System.nanoTime();
                    entry.exitCode = parts.length > 2 ? parseInt(parts[2]) : null;
                    entry.cwd = parts.length > 3 ? parts[3].strip() : null;
                    entry.stdout.dropTrailingNewline();
                    stdoutCurrent = -1;
                }
                return;
            }
            if (stdoutCurrent >= 0) {
                entries[stdoutCurrent].stdout.append(line);
            }
        }

        synchronized void stderrLine(String line) {
            String bare = strip(line);
            if (bare.startsWith(marker)) {
                String[] parts = bare.substring(marker.length()).split(":", 4);
                int index = index(parts);
                if (index < 0) {
                    return;
                }
                if ("B".equals(parts[0])) {
                    stderrCurrent = index;
                } else if ("E".equals(parts[0])) {
                    entries[index].stderr.dropTrailingNewline();
                    stderrCurrent = -1;
                }
                return;
            }
            if (stderrCurrent >= 0) {
                entries[stderrCurrent].stderr.append(line);
            }
        }

        private int index(String[] parts) {
            if (parts.length < 2) {
                return -1;
            }
            Integer n = parseInt(parts[1]);
            return n == null || n < 1 || n > entries.length ? -1 : n - 1;
        }

        synchronized boolean truncated() {
            for (Entry entry : entries) {
                if (entry.stdout.truncated() || entry.stderr.truncated()) {
                    return true;
                }
            }
            return false;
        }

        /** Le relevé JSON rendu à la gateway. */
        synchronized String report(ShellElection shell, ProofHost host, String startCwd, int processExit) {
            ObjectNode root = MAPPER.createObjectNode();
            // Ce qu'il faut pour que l'image ressemble au vrai terminal (amendement PO du 2026-10-24).
            root.put("shell", shell.declaredName());
            root.put("os", host.os());
            root.put("user", host.user());
            root.put("host", host.host());
            root.put("home", host.home());
            root.put("interactiveShell", host.interactiveShell());
            root.put("execShell", host.execShell());
            root.put("cwd", startCwd);
            root.put("processExitCode", processExit);
            ArrayNode list = root.putArray("commands");
            long now = System.nanoTime();
            for (int i = 0; i < entries.length; i++) {
                Entry entry = entries[i];
                ObjectNode node = list.addObject();
                node.put("index", i + 1);
                node.put("command", commands.get(i));
                Status status = entry.ended ? Status.DONE : entry.begun ? Status.INTERRUPTED : Status.NOT_RUN;
                node.put("status", status.wire);
                node.put("stdout", entry.stdout.text());
                node.put("stderr", entry.stderr.text());
                node.put("truncated", entry.stdout.truncated() || entry.stderr.truncated());
                if (status == Status.DONE && entry.exitCode != null) {
                    node.put("exitCode", entry.exitCode);
                } else if (status == Status.INTERRUPTED) {
                    node.put("exitCode", processExit);
                } else {
                    node.putNull("exitCode");
                }
                if (entry.begun) {
                    long end = entry.ended ? entry.endNanos : now;
                    node.put("durationMs", Math.max(0, TimeUnit.NANOSECONDS.toMillis(end - entry.startNanos)));
                    node.put("startedAt", entry.startedAt.toString());
                } else {
                    node.putNull("durationMs");
                    node.putNull("startedAt");
                }
                String cwd = entry.cwd;
                if (cwd != null && !cwd.isBlank()) {
                    node.put("cwd", cwd);
                } else {
                    node.putNull("cwd");
                }
            }
            try {
                return MAPPER.writeValueAsString(root);
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Ce qu'une commande a produit sur un flux. */
    private static final class Entry {
        boolean begun;
        boolean ended;
        long startNanos;
        long endNanos;
        Instant startedAt;
        Integer exitCode;
        String cwd;
        final Capped stdout = new Capped();
        final Capped stderr = new Capped();
    }

    /** Un flux borné : tête et fin gardées, le milieu compté et signalé. */
    static final class Capped {
        private final StringBuilder head = new StringBuilder();
        private final StringBuilder tail = new StringBuilder();
        private long omitted;

        void append(String text) {
            int room = HEAD_CHARS - head.length();
            if (room > 0) {
                String part = text.length() <= room ? text : text.substring(0, room);
                head.append(part);
                text = text.substring(part.length());
            }
            if (text.isEmpty()) {
                return;
            }
            tail.append(text);
            if (tail.length() > TAIL_CHARS * 2) {
                int cut = tail.length() - TAIL_CHARS;
                omitted += cut;
                tail.delete(0, cut);
            }
        }

        /** Retire le saut de ligne posé par le script avant la borne de fin. */
        void dropTrailingNewline() {
            StringBuilder last = tail.length() > 0 ? tail : head;
            int n = last.length();
            if (n > 0 && last.charAt(n - 1) == '\n') {
                last.setLength(n - 1);
                if (last.length() > 0 && last.charAt(last.length() - 1) == '\r') {
                    last.setLength(last.length() - 1);
                }
            }
        }

        boolean truncated() {
            return omitted > 0 || tail.length() > TAIL_CHARS;
        }

        String text() {
            if (tail.length() > TAIL_CHARS) {
                int cut = tail.length() - TAIL_CHARS;
                omitted += cut;
                tail.delete(0, cut);
            }
            if (omitted == 0) {
                return head.toString() + tail;
            }
            return head + "\n… [" + omitted + " caractères omis] …\n" + tail;
        }
    }

    // ------------------------------------------------------------------ outils

    private static String strip(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == '\n' || line.charAt(end - 1) == '\r')) {
            end--;
        }
        return line.substring(0, end).strip();
    }

    private static Integer parseInt(String text) {
        try {
            return Integer.parseInt(text.strip());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void closeQuietly(java.io.OutputStream stream) {
        try {
            stream.close();
        } catch (IOException e) {
            // Processus déjà terminé.
        }
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // Fichier temporaire : le système le nettoiera.
        }
    }

    private static void join(Thread thread) {
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
