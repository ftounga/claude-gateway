package fr.claudegateway.runner;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Invocation <b>bornée</b> de {@code git} sur le poste (F-150 / SF-150-01), pour le cycle de vie des
 * worktrees de la sous-boucle {@code task}.
 *
 * <p><b>git est appelé directement</b>, jamais via l'interpréteur élu ({@link BashTool}). Créer ou
 * retirer un worktree est une opération d'<b>orchestration</b> de la gateway, pas une commande de
 * l'utilisateur : elle n'a rien à faire sous le shell, et rester hors du shell la garde portable tant
 * que {@code git} est présent (le repli sans git est un <b>refus propre</b>, décision PO).</p>
 *
 * <p>Ne lève jamais : une invocation impossible (binaire absent) rend {@code present=false}, un git
 * qui répond en erreur rend son {@code exitCode} et sa {@code stderr}. L'appelant décide du code
 * d'outil à produire.</p>
 */
public class GitCli {

    /** Délai par défaut d'une invocation git (ms) : un {@code worktree add} peut copier un arbre. */
    static final long DEFAULT_TIMEOUT_MS = 60_000L;

    private final String executable;

    public GitCli() {
        this("git");
    }

    /** @param executable nom ou chemin du binaire git (les tests peuvent le forcer) */
    public GitCli(String executable) {
        this.executable = executable;
    }

    /**
     * Résultat d'une invocation git.
     *
     * @param present {@code false} si le binaire {@code git} est introuvable sur ce poste
     * @param exitCode code de sortie du processus ({@code -1} si non lancé ou interrompu)
     */
    public record Result(boolean present, int exitCode, String stdout, String stderr) {

        /** Vrai si git était présent et a terminé sans erreur. */
        public boolean ok() {
            return present && exitCode == 0;
        }
    }

    /** Invocation avec le délai par défaut. */
    public Result run(Path workingDir, String... args) {
        return run(workingDir, DEFAULT_TIMEOUT_MS, args);
    }

    /**
     * Lance {@code git <args>} depuis {@code workingDir}, borné par {@code timeoutMs}. Les deux flux
     * sont pompés sur des threads dédiés pour ne jamais se bloquer sur un tube plein.
     */
    public Result run(Path workingDir, long timeoutMs, String... args) {
        List<String> command = new ArrayList<>();
        command.add(executable);
        for (String arg : args) {
            command.add(arg);
        }
        ProcessBuilder builder = new ProcessBuilder(command);
        if (workingDir != null) {
            builder.directory(workingDir.toFile());
        }
        builder.redirectErrorStream(false);

        Process process;
        try {
            process = builder.start();
        } catch (IOException | RuntimeException e) {
            // Binaire git absent (ou non exécutable) : ce n'est pas une panne, c'est un refus propre.
            return new Result(false, -1, "", "");
        }

        StringBuilder out = new StringBuilder();
        StringBuilder err = new StringBuilder();
        Thread pumpOut = pump(process.getInputStream(), out);
        Thread pumpErr = pump(process.getErrorStream(), err);
        try {
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                join(pumpOut);
                join(pumpErr);
                return new Result(true, -1, out.toString(), "délai git dépassé");
            }
            join(pumpOut);
            join(pumpErr);
            return new Result(true, process.exitValue(), out.toString().trim(), err.toString().trim());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return new Result(true, -1, out.toString(), "git interrompu");
        }
    }

    private static Thread pump(InputStream stream, StringBuilder sink) {
        Thread thread = new Thread(() -> {
            byte[] buffer = new byte[4_096];
            try (InputStream in = stream) {
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    synchronized (sink) {
                        sink.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                    }
                }
            } catch (IOException e) {
                // Flux coupé : rien de plus à lire.
            }
        }, "runner-git-pump");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void join(Thread thread) {
        try {
            thread.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
