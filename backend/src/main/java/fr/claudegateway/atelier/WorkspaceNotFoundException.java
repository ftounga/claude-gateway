package fr.claudegateway.atelier;

/**
 * Levée lorsqu'un workspace n'existe pas ou n'appartient pas à l'utilisateur courant. Mappée en 404
 * (indiscernable : ne révèle jamais l'existence d'un workspace d'un autre utilisateur).
 */
public class WorkspaceNotFoundException extends RuntimeException {

    public WorkspaceNotFoundException(String message) {
        super(message);
    }
}
