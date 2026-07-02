package fr.claudegateway.admin;

/**
 * Accès refusé à l'API d'administration : l'utilisateur courant n'est ni {@code ADMIN} ni le
 * super-admin configuré. Mappée en <b>403</b> par le {@code GlobalExceptionHandler}.
 */
public class AdminForbiddenException extends RuntimeException {

    public AdminForbiddenException() {
        super("Accès réservé à l'administration.");
    }
}
