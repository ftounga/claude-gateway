package fr.claudegateway.atelier.permission;

/**
 * Effet d'une règle de permission d'outil de la boucle maison (F-121 / SF-121-02).
 *
 * <p>Trois états, à l'image de Claude Code : {@link #ALLOW} exécute sans demander, {@link #ASK}
 * demande une autorisation à l'écran avant d'émettre, {@link #DENY} refuse d'emblée avec un motif que
 * le modèle reçoit. C'est ce qui remplace le modèle binaire (autoriser/refuser seulement) de la porte
 * de confirmation d'avant SF-121-02.</p>
 */
public enum PermissionEffect {
    ALLOW,
    ASK,
    DENY;

    /** Lit un effet depuis sa forme stockée, en repli sûr sur {@link #ASK} pour une valeur inconnue. */
    public static PermissionEffect fromStored(String value) {
        if (value == null) {
            return ASK;
        }
        try {
            return PermissionEffect.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            // Une valeur illisible ne doit jamais ouvrir la vanne : on demande plutôt que d'autoriser.
            return ASK;
        }
    }
}
