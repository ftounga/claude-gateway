package fr.claudegateway.agent;

/**
 * Ce que le fournisseur dit d'un appel, en termes neutres (F-276 / SF-276-01) : l'identifiant de sa
 * réponse (pour chaîner le diagnostic de cache de l'appel suivant), où son cache a cassé, et combien
 * de blocs de raisonnement lui ont été envoyés. Des identifiants, des types et des nombres — jamais
 * de contenu.
 *
 * @param responseId        identifiant de la réponse, {@code null} s'il est inconnu
 * @param cacheMissReason   cause de rupture du cache telle que nommée par le fournisseur
 *                          ({@code messages_changed}, {@code system_changed}…), {@code none} si rien
 *                          n'a divergé, {@code pending} si la comparaison n'était pas finie,
 *                          {@code null} si aucun diagnostic n'a été demandé
 * @param cacheMissedTokens tokens d'entrée situés après le point de rupture (estimation du
 *                          fournisseur), {@code null} hors rupture
 * @param thinkingReplayed  blocs de raisonnement (signés ou masqués) présents dans la requête
 */
public record AgentCallDiagnostics(String responseId, String cacheMissReason, Long cacheMissedTokens,
        int thinkingReplayed) {

    /** Rien de connu : un fournisseur qui ne dit rien, ou un appel bouchonné. */
    public static final AgentCallDiagnostics NONE = new AgentCallDiagnostics(null, null, null, 0);

    public AgentCallDiagnostics {
        responseId = responseId == null || responseId.isBlank() ? null : responseId;
        cacheMissReason = cacheMissReason == null || cacheMissReason.isBlank() ? null : cacheMissReason;
        thinkingReplayed = Math.max(0, thinkingReplayed);
    }
}
