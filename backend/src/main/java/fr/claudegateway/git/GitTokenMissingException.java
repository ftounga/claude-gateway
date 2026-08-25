package fr.claudegateway.git;

/**
 * Aucun jeton GitHub enregistré pour l'utilisateur (F-31 / SF-31-02) alors qu'une opération sur un
 * dépôt en exige un : création d'un workspace {@code GIT}, montage de session, lecture ou push.
 *
 * <p>Distinct d'un jeton <b>refusé</b> ({@link InvalidGitTokenException}) : ici il n'y a rien à
 * refuser, et l'action corrective est différente — enregistrer un jeton, plutôt qu'en changer.</p>
 */
public class GitTokenMissingException extends RuntimeException {

    public GitTokenMissingException(String message) {
        super(message);
    }
}
