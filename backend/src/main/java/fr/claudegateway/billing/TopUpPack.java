package fr.claudegateway.billing;

/**
 * Pack de tokens rachetable ponctuellement (top-up, F-21 / SF-21-02). Décrit un achat one-shot :
 * combien de tokens sont crédités au quota de la période courante. Le montant de tokens est
 * <b>autoritatif côté serveur</b> (jamais fourni par le client) ; le prix et le price ID Stripe
 * vivent dans la configuration (env), jamais en dur ici — parallèle de {@link Plan} (OQ-07).
 *
 * @param code   code stable du pack (clé de mapping vers le price ID Stripe)
 * @param label  libellé affichable
 * @param tokens nombre de tokens crédités à l'achat (> 0)
 */
public record TopUpPack(String code, String label, long tokens) {
}
