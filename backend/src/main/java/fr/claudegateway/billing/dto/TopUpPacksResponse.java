package fr.claudegateway.billing.dto;

import java.util.List;

/**
 * Enveloppe de réponse du catalogue de packs de tokens (F-21 / SF-21-02).
 *
 * <p>La projection de chaque pack est faite par l'appelant, qui seul dispose de la configuration
 * portant les <b>montants d'affichage</b> (F-67). Il n'existe volontairement plus de fabrique
 * {@code from(List&lt;TopUpPack&gt;)} : elle construisait des packs <b>sans prix</b>, et un futur
 * appelant l'aurait reprise sans voir qu'il venait de retirer le montant de l'écran.</p>
 *
 * @param packs liste des packs rachetables
 */
public record TopUpPacksResponse(List<TopUpPackResponse> packs) {
}
