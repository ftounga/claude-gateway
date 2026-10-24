package fr.claudegateway.runner.video.render;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import fr.claudegateway.runner.OperatingSystem;
import fr.claudegateway.runner.teams.LocalToolchain;

/**
 * <b>Les processus du rendu</b> (F-215 / SF-215-02) : attendre une commande courte (vérifier Node, installer
 * hyperframes, préparer Chrome) ou lancer un rendu long <b>détaché</b>.
 *
 * <p>Une interface pour être éprouvable sans rien lancer. Les arguments passent un par un, jamais par un
 * shell (leçon de SF-38-23).</p>
 */
public interface RenderProcesses {

    /**
     * Lance une commande et attend sa fin.
     *
     * @throws IOException si le programme n'a pas pu être lancé du tout (introuvable, exécution refusée)
     */
    Result run(List<String> command, Path workingDir, Map<String, String> env, long timeoutMs) throws IOException;

    /**
     * Lance une commande <b>détachée</b> : sortie et erreurs dans {@code log}, aucun tube vers le runner, groupe
     * de processus propre quand le système le permet. Rend tout de suite.
     *
     * @return l'identifiant du processus
     */
    long launchDetached(List<String> command, Path workingDir, Map<String, String> env, Path log) throws IOException;

    /** Vrai si ce processus tourne encore. */
    boolean alive(long pid);

    /**
     * Ce qu'une commande a rendu.
     *
     * @param exitCode code de sortie ; {@code -1} si tuée au délai
     * @param output   sortie standard et d'erreur, ligne par ligne
     */
    record Result(int exitCode, List<String> output, boolean timedOut) {

        public Result {
            output = output == null ? List.of() : List.copyOf(output);
        }

        public boolean succeeded() {
            return exitCode == 0 && !timedOut;
        }

        /** Les {@code lines} dernières lignes non vides. */
        public String tail(int lines) {
            List<String> kept = output.stream().filter(l -> !l.isBlank()).toList();
            int from = Math.max(0, kept.size() - Math.max(1, lines));
            return String.join(" | ", kept.subList(from, kept.size()));
        }
    }

    /** L'exécution réelle. */
    static RenderProcesses real() {
        return new RenderProcesses() {
            @Override
            public Result run(List<String> command, Path workingDir, Map<String, String> env, long timeoutMs)
                    throws IOException {
                ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
                if (workingDir != null) {
                    builder.directory(workingDir.toFile());
                }
                if (env != null) {
                    builder.environment().putAll(env);
                }
                Process process = builder.start();
                process.getOutputStream().close();
                List<String> out = Collections.synchronizedList(new ArrayList<>());
                Thread reader = new Thread(() -> {
                    try (BufferedReader in = new BufferedReader(
                            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = in.readLine()) != null) {
                            if (out.size() < 2_000) {
                                out.add(line);
                            }
                        }
                    } catch (IOException ignored) {
                        // Flux coupé : le code de sortie fera foi.
                    }
                }, "render-drain");
                reader.setDaemon(true);
                reader.start();
                try {
                    boolean finished = process.waitFor(Math.max(1_000L, timeoutMs), TimeUnit.MILLISECONDS);
                    if (!finished) {
                        process.destroyForcibly();
                    }
                    reader.join(2_000L);
                    synchronized (out) {
                        return new Result(finished ? process.exitValue() : -1, List.copyOf(out), !finished);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    process.destroyForcibly();
                    return new Result(-1, List.of(), true);
                }
            }

            @Override
            public long launchDetached(List<String> command, Path workingDir, Map<String, String> env, Path log)
                    throws IOException {
                List<String> full = new ArrayList<>();
                // Groupe de processus propre : un Ctrl+C sur le runner ne tue pas le rendu. `setsid` existe sous
                // Linux ; ailleurs, le mode détaché d'HyperFrames et le journal fichier suffisent (Windows ne
                // tue pas les enfants à la mort du parent).
                if (OperatingSystem.current() != OperatingSystem.WINDOWS
                        && LocalToolchain.lookupOnPath("setsid") != null) {
                    full.add("setsid");
                }
                full.addAll(command);
                ProcessBuilder builder = new ProcessBuilder(full)
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                        .redirectInput(ProcessBuilder.Redirect.from(new File(
                                OperatingSystem.current() == OperatingSystem.WINDOWS ? "NUL" : "/dev/null")));
                if (workingDir != null) {
                    builder.directory(workingDir.toFile());
                }
                if (env != null) {
                    builder.environment().putAll(env);
                }
                return builder.start().pid();
            }

            @Override
            public boolean alive(long pid) {
                return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
            }
        };
    }
}
