package fr.claudegateway.diagnostic;

/**
 * Le projet désigné <b>n'est pas</b> le dépôt de l'application (F-157 / SF-157-02).
 *
 * <p>Refuser est essentiel : lire un code sans rapport ferait conclure n'importe quoi, <b>avec
 * assurance</b>. Mieux vaut ne rien dire que dire faux.</p>
 */
public class RepositoryNotRecognizedException extends RuntimeException {

    public RepositoryNotRecognizedException(String message) {
        super(message);
    }
}
