package fr.claudegateway.atelier;

/**
 * Accès refusé à l'Atelier (F-28 / SF-28-06) : l'utilisateur courant n'est ni {@code ADMIN} ni
 * abonné à l'offre Gold active. Mappée en <b>403</b> ({@code atelier_forbidden}) par le
 * {@code GlobalExceptionHandler}.
 */
public class AtelierAccessDeniedException extends RuntimeException {

    public AtelierAccessDeniedException() {
        super("Accès à l'Atelier réservé à l'offre Gold.");
    }
}
