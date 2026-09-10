package fr.claudegateway.access;

/**
 * Code <b>nominatif</b> saisi par un autre compte que celui visé (F-62). Mappée en <b>403</b>
 * ({@code access_code_not_for_account}).
 */
public class AccessCodeNotForAccountException extends RuntimeException {

    public AccessCodeNotForAccountException() {
        super("Ce code est réservé à un autre compte.");
    }
}
