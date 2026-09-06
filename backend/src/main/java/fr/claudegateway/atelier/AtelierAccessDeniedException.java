package fr.claudegateway.atelier;

/**
 * Accès refusé à l'Atelier (F-28 / SF-28-06, amendé F-40 / SF-40-01) : l'utilisateur courant n'est
 * ni {@code ADMIN} ni détenteur du <b>droit d'Atelier</b> — offre Gold active, ou option Atelier
 * active sur un plan Solo/Pro actif. Mappée en <b>403</b> ({@code atelier_forbidden}) par le
 * {@code GlobalExceptionHandler}.
 */
public class AtelierAccessDeniedException extends RuntimeException {

    public AtelierAccessDeniedException() {
        super("L'Atelier demande l'offre Gold, ou l'option Atelier ajoutée à votre offre.");
    }
}
