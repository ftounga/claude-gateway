package fr.claudegateway.byok;

/**
 * Levée lorsqu'une opération de chiffrement/déchiffrement BYOK est demandée alors qu'aucun
 * mécanisme n'est configuré (ni AWS KMS ni clé locale). Traduite en {@code 503 byok_unavailable}
 * par le {@code GlobalExceptionHandler}. Conforme à la règle « ne pas empêcher le démarrage » :
 * l'indisponibilité n'échoue qu'au moment d'un usage réel.
 */
public class ByokDisabledException extends RuntimeException {

    public ByokDisabledException(String message) {
        super(message);
    }
}
