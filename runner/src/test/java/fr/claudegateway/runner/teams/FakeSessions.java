package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>Un `ffmpeg` de capture, de papier</b> (F-91 / SF-91-01), sur le modèle de {@link FakeProcesses}.
 *
 * <p>Il existe pour une raison écrite dans la mini-spec et qu'on ne maquille pas : <b>le CI n'a ni
 * {@code ffmpeg}, ni écran, ni périphérique audio</b>. Ce qui est éprouvé ici est ce que le produit
 * <b>décide</b> — refuser sans filigrane, refuser sans confirmation, assembler une ligne de commande,
 * arrêter proprement, constater un fichier vide — et non ce qu'{@code ffmpeg} fait d'un écran réel.</p>
 */
final class FakeSessions implements ProcessSession {

    /** Ce qui a été lancé, dans l'ordre. */
    final List<List<String>> calls = new ArrayList<>();
    final List<Handle> handles = new ArrayList<>();

    private IOException failure;
    private boolean diesAtOnce;
    private boolean ignoresGracefulStop;
    private List<String> log = List.of();
    /** Le fichier que la capture est censée écrire ; posé à l'arrêt propre. */
    private long bytesOnStop = 4_096L;

    FakeSessions unlaunchable(IOException value) {
        this.failure = value;
        return this;
    }

    FakeSessions dyingAtOnce(List<String> tail) {
        this.diesAtOnce = true;
        this.log = List.copyOf(tail);
        return this;
    }

    FakeSessions deaf() {
        this.ignoresGracefulStop = true;
        return this;
    }

    FakeSessions producingNothing() {
        this.bytesOnStop = 0L;
        return this;
    }

    @Override
    public Handle start(List<String> command, Path workingDir) throws IOException {
        calls.add(List.copyOf(command));
        if (failure != null) {
            throw failure;
        }
        Handle handle = new FakeHandle(Path.of(command.get(command.size() - 1)));
        handles.add(handle);
        return handle;
    }

    List<String> lastCall() {
        return calls.isEmpty() ? List.of() : calls.get(calls.size() - 1);
    }

    /** Le handle rendu au dernier démarrage. */
    FakeHandle lastHandle() {
        return handles.isEmpty() ? null : (FakeHandle) handles.get(handles.size() - 1);
    }

    /** Un processus de papier : vivant jusqu'à ce qu'on lui demande de s'arrêter. */
    final class FakeHandle implements Handle {

        private final Path output;
        boolean stopRequested;
        boolean destroyed;
        private boolean running = !diesAtOnce;

        FakeHandle(Path output) {
            this.output = output;
        }

        @Override
        public boolean alive() {
            return running;
        }

        @Override
        public void requestStop() {
            stopRequested = true;
            if (ignoresGracefulStop) {
                return;
            }
            running = false;
            write();
        }

        @Override
        public int awaitExit(long timeoutMs) {
            return running ? -1 : 0;
        }

        @Override
        public void destroy() {
            destroyed = true;
            if (running) {
                running = false;
                write();
            }
        }

        @Override
        public List<String> tail() {
            return log;
        }

        private void write() {
            if (bytesOnStop <= 0L) {
                return;
            }
            try {
                java.nio.file.Files.createDirectories(output.getParent());
                java.nio.file.Files.write(output, new byte[(int) bytesOnStop]);
            } catch (IOException e) {
                throw new IllegalStateException("le fichier de papier n'a pas pu être écrit", e);
            }
        }
    }
}
