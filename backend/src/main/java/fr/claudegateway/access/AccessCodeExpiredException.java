package fr.claudegateway.access;

/**
 * Code d'accès périmé avant d'avoir servi (F-62). Mappée en <b>409</b>
 * ({@code access_code_expired}).
 *
 * <p>À ne pas confondre avec la fin d'un droit : ici le code n'a jamais été consommé, sa date de
 * validité est simplement passée.</p>
 */
public class AccessCodeExpiredException extends RuntimeException {

    public AccessCodeExpiredException() {
        super("Ce code a expiré.");
    }
}
