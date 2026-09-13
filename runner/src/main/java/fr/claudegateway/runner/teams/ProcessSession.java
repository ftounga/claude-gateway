package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <b>Le port d'un processus qui DURE</b> (F-91 / SF-91-01).
 *
 * <h2>Pourquoi un second port, alors que {@link ProcessRunner} existe</h2>
 *
 * <p>{@code ProcessRunner} lance et <b>attend la fin</b> : c'est exactement ce qu'il faut pour
 * extraire des images d'un fichier (F-90). Une capture, elle, <b>n'a pas de fin connue</b> — elle
 * dure jusqu'à ce qu'on l'arrête, et pendant ce temps l'appel d'outil qui l'a démarrée doit
 * <b>rendre la main</b>. Étendre {@code ProcessRunner} aurait mélangé deux contrats ; ce port en
 * ajoute un.</p>
 *
 * <h2>L'arrêt propre, et pourquoi il compte</h2>
 *
 * <p>Tuer {@code ffmpeg} laisse un conteneur MP4 <b>sans son index</b> : le fichier pèse ses
 * centaines de mégaoctets et presque aucun lecteur ne l'ouvre. C'est la pire issue possible pour ce
 * volet — un artefact qui <i>semble</i> exister. {@code ffmpeg} ferme proprement quand il lit un
 * {@code q} sur son entrée standard : {@link Handle#requestStop()} le lui écrit, et l'interruption
 * n'arrive qu'après, en dernier recours.</p>
 *
 * <h2>Éprouvable sans {@code ffmpeg}</h2>
 *
 * <p>Le CI n'a ni {@code ffmpeg}, ni écran, ni périphérique audio. Ce port est la couture qui permet
 * d'éprouver tout ce que le produit <b>décide</b> — refuser, assembler, arrêter, constater un
 * fichier vide — sans qu'aucun binaire ne tourne.</p>
 */
@FunctionalInterface
public interface ProcessSession {

    /**
     * Lance un processus et rend la main <b>tout de suite</b>.
     *
     * @param command    programme et arguments, un par élément — jamais une ligne à découper
     * @param workingDir dossier de travail, ou {@code null}
     * @throws IOException si le programme n'a pas pu être lancé du tout
     */
    Handle start(List<String> command, Path workingDir) throws IOException;

    /** Le processus lancé, vu par celui qui l'a démarré. */
    interface Handle {

        /** Vrai tant qu'il tourne. */
        boolean alive();

        /**
         * Demande l'arrêt <b>propre</b> : un {@code q} sur l'entrée standard. Sans effet si le
         * processus est déjà fini.
         */
        void requestStop();

        /**
         * Attend la fin.
         *
         * @return le code de sortie, ou {@code -1} s'il tourne encore au bout du délai
         */
        int awaitExit(long timeoutMs);

        /** Interruption en dernier recours, quand l'arrêt propre n'a rien donné. */
        void destroy();

        /**
         * Les dernières lignes du journal, telles quelles. {@code ffmpeg} écrit tout sur
         * {@code stderr} : quand il refuse un périphérique, la raison est là et nulle part ailleurs.
         */
        List<String> tail();
    }

    /** L'exécution réelle : {@link ProcessBuilder}, sans shell, journal lu au fil de l'eau. */
    static ProcessSession real() {
        return (command, workingDir) -> {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workingDir != null) {
                builder.directory(workingDir.toFile());
            }
            builder.redirectErrorStream(true);
            Process process = builder.start();
            List<String> log = Collections.synchronizedList(new ArrayList<>());
            Thread reader = new Thread(() -> {
                try (var stream = process.getInputStream();
                        var buffered = new java.io.BufferedReader(
                                new java.io.InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = buffered.readLine()) != null) {
                        // Un journal de capture d'une heure tiendrait des milliers de lignes pour
                        // rien : seules les dernières servent à dire pourquoi ça a échoué.
                        if (log.size() >= 200) {
                            log.remove(0);
                        }
                        log.add(line);
                    }
                } catch (IOException e) {
                    // Un flux coupé n'est pas une erreur en soi : le code de sortie fera foi.
                }
            }, "teams-capture-log");
            reader.setDaemon(true);
            reader.start();
            return new Handle() {
                @Override
                public boolean alive() {
                    return process.isAlive();
                }

                @Override
                public void requestStop() {
                    if (!process.isAlive()) {
                        return;
                    }
                    try (OutputStream stdin = process.getOutputStream()) {
                        stdin.write("q\n".getBytes(StandardCharsets.UTF_8));
                        stdin.flush();
                    } catch (IOException e) {
                        // Entrée fermée : l'interruption prendra le relais, et le dira.
                    }
                }

                @Override
                public int awaitExit(long timeoutMs) {
                    try {
                        return process.waitFor(Math.max(1L, timeoutMs), TimeUnit.MILLISECONDS)
                                ? process.exitValue() : -1;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return -1;
                    }
                }

                @Override
                public void destroy() {
                    process.destroyForcibly();
                }

                @Override
                public List<String> tail() {
                    synchronized (log) {
                        int from = Math.max(0, log.size() - 8);
                        return List.copyOf(log.subList(from, log.size()));
                    }
                }
            };
        };
    }
}
