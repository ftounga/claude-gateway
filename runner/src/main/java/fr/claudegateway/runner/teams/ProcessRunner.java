package fr.claudegateway.runner.teams;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <b>Le port d'exécution d'un processus</b> (F-90 / SF-90-01).
 *
 * <p>Une interface, et non un appel direct à {@link ProcessBuilder}, pour une raison unique et
 * décisive : <b>le CI n'a pas {@code ffmpeg}</b>, et nous n'avons aucun compte Teams de test. Tout
 * ce que F-90 sait faire — lire un journal {@code showinfo}, dédoublonner, aligner, refuser — doit
 * donc pouvoir s'éprouver sans qu'aucun binaire ne tourne. Ce port est la couture qui le permet.</p>
 *
 * <p>Ce qu'il ne fait pas : interpréter une ligne de commande. Les arguments sont passés
 * <b>un par un</b>, jamais concaténés dans un shell — un chemin de fichier avec un espace ou un
 * point-virgule ne peut pas devenir une commande (leçon de SF-38-23).</p>
 */
@FunctionalInterface
public interface ProcessRunner {

    /**
     * Lance une commande et attend sa fin.
     *
     * @param command    programme et arguments, un par élément — jamais une ligne à découper
     * @param workingDir dossier de travail, ou {@code null} pour celui du runner
     * @param timeoutMs  délai au-delà duquel le processus est tué
     * @return ce que le processus a rendu, y compris quand il a échoué
     * @throws IOException si le programme n'a pas pu être lancé du tout
     */
    ProcessResult run(List<String> command, Path workingDir, long timeoutMs) throws IOException;

    /**
     * Ce qu'un processus a rendu.
     *
     * <p>{@link #tail()} existe pour une raison précise : quand {@code ffmpeg} échoue, dire
     * « échec » serait inutilisable. Les dernières lignes de sa sortie disent presque toujours
     * pourquoi — c'est elles qu'on remonte, telles quelles.</p>
     *
     * @param exitCode code de sortie ; {@code -1} si le processus a été tué au délai
     * @param stdout   sortie standard, ligne par ligne
     * @param stderr   sortie d'erreur, ligne par ligne — {@code ffmpeg} y écrit tout son journal
     * @param timedOut vrai si le délai a été dépassé
     */
    record ProcessResult(int exitCode, List<String> stdout, List<String> stderr, boolean timedOut) {

        public ProcessResult {
            stdout = stdout == null ? List.of() : List.copyOf(stdout);
            stderr = stderr == null ? List.of() : List.copyOf(stderr);
        }

        public boolean succeeded() {
            return exitCode == 0 && !timedOut;
        }

        /** Les {@code lines} dernières lignes d'erreur, prêtes à être lues par un humain. */
        public String tail(int lines) {
            List<String> source = stderr.isEmpty() ? stdout : stderr;
            int from = Math.max(0, source.size() - Math.max(1, lines));
            return String.join(System.lineSeparator(), source.subList(from, source.size()));
        }

        /** Les cinq dernières lignes : assez pour comprendre, trop peu pour noyer. */
        public String tail() {
            return tail(5);
        }
    }

    /** L'exécution réelle : {@link ProcessBuilder}, sans shell, sortie lue au fil de l'eau. */
    static ProcessRunner real() {
        return (command, workingDir, timeoutMs) -> {
            ProcessBuilder builder = new ProcessBuilder(command);
            if (workingDir != null) {
                builder.directory(workingDir.toFile());
            }
            Process process = builder.start();
            List<String> out = java.util.Collections.synchronizedList(new ArrayList<>());
            List<String> err = java.util.Collections.synchronizedList(new ArrayList<>());
            Thread outReader = drain(process.getInputStream(), out);
            Thread errReader = drain(process.getErrorStream(), err);
            boolean finished;
            try {
                finished = process.waitFor(Math.max(1_000L, timeoutMs), TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                }
                outReader.join(2_000L);
                errReader.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                return new ProcessResult(-1, snapshot(out), snapshot(err), true);
            }
            return new ProcessResult(finished ? process.exitValue() : -1, snapshot(out),
                    snapshot(err), !finished);
        };
    }

    /** Copie stable d'une liste alimentée par un autre fil. */
    private static List<String> snapshot(List<String> lines) {
        synchronized (lines) {
            return List.copyOf(lines);
        }
    }

    /** Lit un flux dans une liste, sur son propre fil : un tampon plein bloquerait le processus. */
    private static Thread drain(java.io.InputStream stream, List<String> into) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    into.add(line);
                }
            } catch (IOException e) {
                // Un flux coupé n'est pas une erreur en soi : le code de sortie fera foi.
            }
        }, "process-drain");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }
}
