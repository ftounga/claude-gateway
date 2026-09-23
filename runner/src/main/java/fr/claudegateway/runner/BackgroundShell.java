package fr.claudegateway.runner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Un processus lancé <b>en arrière-plan</b> (F-121 / SF-121-07) : il survit à l'appel {@code bash} qui
 * l'a démarré, sa sortie est <b>bufferisée</b> (et non diffusée, faute d'un {@code tool_result} à
 * précéder), et on la relit par {@code bash_output} avec un curseur — chaque lecture ne rend que ce
 * qui est nouveau.
 *
 * <p>Deux threads pompent {@code stdout} et {@code stderr} dans un tampon commun, borné : au-delà de
 * {@link #MAX_BUFFER_CHARS}, on coupe par la <b>tête</b> (le début), le curseur est réajusté, et un
 * marqueur le signale à la première relecture qui suit. L'état est déduit sans thread d'attente
 * dédié : tant que le processus vit, il est {@code EN COURS} ; sinon on lit son code de sortie une
 * fois, ou {@code ARRÊTÉ} si {@link #kill()} est passé par là.</p>
 */
final class BackgroundShell {

    /** État d'un processus de fond, lu par {@code bash_output}. */
    enum State { RUNNING, EXITED, KILLED }

    /** Tampon maximal conservé par shell. Au-delà, on coupe par la tête (le début). */
    static final int MAX_BUFFER_CHARS = 1_048_576;

    private final String id;
    private final String command;
    private final Process process;
    private final long startedAt = System.currentTimeMillis();

    private final StringBuilder buffer = new StringBuilder();
    private int consumed;
    private boolean headDropped;
    private boolean headDroppedReported;
    private State state = State.RUNNING;
    private Integer exitCode;

    BackgroundShell(String id, String command, Process process) {
        this.id = id;
        this.command = command;
        this.process = process;
        // stdin fermé tout de suite : une commande qui lit l'entrée standard reçoit EOF plutôt que de
        // pendre indéfiniment en fond (même garde que le bash synchrone).
        try {
            process.getOutputStream().close();
        } catch (IOException e) {
            // Processus déjà mort : sans conséquence.
        }
        pump(process.getInputStream(), "runner-bg-out-" + id);
        pump(process.getErrorStream(), "runner-bg-err-" + id);
    }

    String id() {
        return id;
    }

    /** Vrai si le processus n'est plus en cours (terminé ou tué) — sert au ménage du registre. */
    synchronized boolean finished() {
        refreshState();
        return state != State.RUNNING;
    }

    /**
     * Sortie <b>nouvelle depuis la dernière lecture</b>, précédée de l'en-tête d'état, et suivie de la
     * ligne d'état finale. Le curseur avance : deux appels de suite ne rejouent pas la même sortie.
     */
    synchronized String drain() {
        refreshState();
        StringBuilder out = new StringBuilder();
        if (headDropped && !headDroppedReported) {
            out.append("… (début tronqué)\n");
            headDroppedReported = true;
        }
        out.append(buffer, consumed, buffer.length());
        consumed = buffer.length();
        String body = out.toString();
        StringBuilder result = new StringBuilder();
        if (!body.isEmpty()) {
            result.append(body);
            if (result.charAt(result.length() - 1) != '\n') {
                result.append('\n');
            }
        }
        result.append(statusLine());
        return result.toString();
    }

    /** Tue le processus (et confirme). Idempotent : un second appel ne fait que redire l'état. */
    synchronized String kill() {
        refreshState();
        if (state == State.RUNNING) {
            process.destroyForcibly();
            state = State.KILLED;
        } else if (state == State.EXITED) {
            return "La commande " + id + " était déjà terminée (" + statusLine() + ").";
        }
        return "Commande " + id + " arrêtée.";
    }

    private String statusLine() {
        return switch (state) {
            case RUNNING -> "[état: en cours]";
            case KILLED -> "[état: arrêté]";
            case EXITED -> "[état: terminé, code de sortie: "
                    + (exitCode == null ? "inconnu" : exitCode) + "]";
        };
    }

    /** Recalcule l'état sans thread d'attente : un processus qui ne vit plus a un code de sortie. */
    private void refreshState() {
        if (state == State.RUNNING && !process.isAlive()) {
            state = State.EXITED;
            try {
                exitCode = process.exitValue();
            } catch (IllegalThreadStateException e) {
                exitCode = null;
            }
        }
    }

    private synchronized void append(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        buffer.append(text);
        if (buffer.length() > MAX_BUFFER_CHARS) {
            int drop = buffer.length() - MAX_BUFFER_CHARS;
            buffer.delete(0, drop);
            consumed = Math.max(0, consumed - drop);
            headDropped = true;
        }
    }

    private void pump(InputStream stream, String threadName) {
        Thread thread = new Thread(() -> {
            byte[] buf = new byte[8_192];
            CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            java.nio.ByteBuffer pending = java.nio.ByteBuffer.allocate(0);
            try (InputStream in = stream) {
                int read;
                while ((read = in.read(buf)) >= 0) {
                    java.nio.ByteBuffer input = java.nio.ByteBuffer.allocate(pending.remaining() + read);
                    input.put(pending);
                    input.put(buf, 0, read);
                    input.flip();
                    java.nio.CharBuffer chars = java.nio.CharBuffer.allocate(input.remaining() + 1);
                    decoder.decode(input, chars, false);
                    pending = java.nio.ByteBuffer.allocate(input.remaining());
                    pending.put(input);
                    pending.flip();
                    chars.flip();
                    append(chars.toString());
                }
            } catch (IOException | RuntimeException e) {
                // Flux coupé (processus tué) : rien de plus à lire, ce n'est pas une erreur.
            }
        }, threadName);
        thread.setDaemon(true);
        thread.start();
    }

    String command() {
        return command;
    }

    long startedAt() {
        return startedAt;
    }
}
