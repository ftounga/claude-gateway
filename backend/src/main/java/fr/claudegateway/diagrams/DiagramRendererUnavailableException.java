package fr.claudegateway.diagrams;

/**
 * Le moteur de rendu n'a pas répondu (F-142 / SF-142-06). <b>Distinct d'un diagramme invalide</b> :
 * ici, le code de l'utilisateur n'est pas en cause, et le repli à proposer n'est pas le même.
 */
public class DiagramRendererUnavailableException extends RuntimeException {

    public DiagramRendererUnavailableException(String message) {
        super(message);
    }
}
