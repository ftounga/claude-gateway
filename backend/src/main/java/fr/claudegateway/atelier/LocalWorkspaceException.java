package fr.claudegateway.atelier;

/**
 * Geste refusé parce que le projet vit <b>sur la machine de l'utilisateur</b> (F-38 / SF-38-15).
 *
 * <p>Trois gestes tombent ici : écrire un fichier dans le stockage, basculer la cible sur le bac à
 * sable, et les opérations Git de F-31. Aucun n'est un bug : ce sont des chemins qui n'ont pas de
 * sens quand les fichiers ne sont pas chez nous. Le message dit donc où le geste doit se faire,
 * plutôt que ce qui a échoué.</p>
 *
 * <p>Mappé en {@code 409 local_workspace_refused} : l'état du projet est en conflit avec le geste,
 * et il le restera tant que le projet est local.</p>
 */
public class LocalWorkspaceException extends RuntimeException {

    public LocalWorkspaceException(String message) {
        super(message);
    }
}
