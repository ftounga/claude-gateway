package fr.claudegateway.atelier.git;

/**
 * Publication demandée sans aucun travail non publié (F-31 / SF-31-12).
 *
 * <p>Rendue en {@code 409 git_nothing_to_publish} : créer un commit vide n'apporterait rien, et
 * laisser croire à une publication réussie serait pire.</p>
 */
public class GitNothingToPublishException extends RuntimeException {

    public GitNothingToPublishException(String message) {
        super(message);
    }
}
