package fr.claudegateway.byok;

/**
 * Levée quand l'offre souscrite est une offre <b>BYOK</b> (F-41) — la plateforme n'alloue aucun
 * jeton, les appels sont servis par la clé du client — mais qu'<b>aucune clé active</b> n'est
 * enregistrée pour cet utilisateur.
 *
 * <p>Sans ce refus, l'appel partirait avec la clé de la <b>plateforme</b> : tous les appelants font
 * {@code resolveActiveApiKey(userId).orElse(null)}, et un {@code null} signifie « mode Hosted ». Ce
 * repli est juste pour un abonné Hosted, et exactement faux pour un abonné BYOK, qui ne paie aucun
 * jeton à la gateway — et que son quota, nul par contrat depuis SF-41-01, ne bloque plus non plus.</p>
 *
 * <p>Traduite en {@code 409 byok_key_required} : le client a payé (donc pas un 402) et il a le droit
 * (donc pas un 403) — il lui manque une clé, et le message doit lui dire où la déposer.</p>
 */
public class ByokKeyRequiredException extends RuntimeException {

    public ByokKeyRequiredException(String message) {
        super(message);
    }
}
