package fr.claudegateway.diagrams;

/** Le diagramme n'a pas pu être rendu, et <b>on sait pourquoi</b> (F-142 / SF-142-06). */
public class DiagramRejectedException extends RuntimeException {

    public DiagramRejectedException(String message) {
        super(message);
    }
}
