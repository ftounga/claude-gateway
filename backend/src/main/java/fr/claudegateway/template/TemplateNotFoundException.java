package fr.claudegateway.template;

/**
 * Modèle de prompt inexistant <b>ou</b> n'appartenant pas à l'utilisateur courant.
 * Volontairement indistinct pour ne pas révéler l'existence d'un modèle d'autrui.
 * Traduit en {@code 404 not_found}.
 */
public class TemplateNotFoundException extends RuntimeException {

    public TemplateNotFoundException() {
        super("Modèle introuvable.");
    }
}
