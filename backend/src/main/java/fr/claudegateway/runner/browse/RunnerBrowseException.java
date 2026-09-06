package fr.claudegateway.runner.browse;

/**
 * Lecture impossible sur la machine de l'utilisateur (F-38 / SF-38-17) : machine absente, chemin
 * exclu par {@code .runnerignore}, ou refus du runner.
 *
 * <p>Mappé en {@code 409 runner_browse_unavailable} : c'est un <b>état</b> du projet, pas une panne
 * de la gateway — et il se répare en lançant le runner.</p>
 */
public class RunnerBrowseException extends RuntimeException {

    public RunnerBrowseException(String message) {
        super(message);
    }
}
