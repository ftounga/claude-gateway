package fr.claudegateway.git;

/**
 * Fichier du dépôt non lisible dans l'explorateur (F-31 / SF-31-03) : binaire, ou plus volumineux que
 * la limite de lecture.
 *
 * <p>Distinct d'un fichier <b>absent</b> : ici le fichier existe, et le dire évite de faire chercher
 * l'utilisateur. Le refus est explicite plutôt que servir un contenu tronqué ou du binaire décodé en
 * texte — un contenu partiel présenté comme complet est pire que pas de contenu du tout.</p>
 */
public class GitFileNotReadableException extends RuntimeException {

    public GitFileNotReadableException(String message) {
        super(message);
    }
}
