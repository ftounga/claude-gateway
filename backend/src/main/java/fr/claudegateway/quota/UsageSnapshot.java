package fr.claudegateway.quota;

import java.time.LocalDate;

/**
 * Instantané de consommation d'un utilisateur pour la période courante (F-10). Objet métier interne
 * renvoyé par {@link QuotaService#currentUsage} et projeté en DTO par le controller.
 *
 * @param usedTokens      tokens <b>facturés</b> sur la période — le décompte que le quota oppose,
 *                        où chaque nature de token pèse son coût (F-63)
 * @param quotaTokens     quota de tokens de la période (selon l'entitlement)
 * @param remainingTokens tokens restants (jamais négatif)
 * @param processedTokens volume de tokens <b>traités</b> sur la période (entrée + sortie). Ce n'est
 *                        pas le décompte : un token de sortie coûte cinq fois un token d'entrée, une
 *                        lecture de cache un dixième. Les deux chiffres sont justes, et l'écran doit
 *                        dire lequel il montre (F-63 / SF-63-03)
 * @param periodStart     premier jour de la période (mois calendaire UTC)
 * @param periodEnd       premier jour de la période suivante (borne exclusive)
 */
public record UsageSnapshot(
        long usedTokens,
        long quotaTokens,
        long remainingTokens,
        long processedTokens,
        LocalDate periodStart,
        LocalDate periodEnd) {

    /**
     * Forme d'avant F-63, conservée pour les appelants qui ne distinguent pas les deux chiffres :
     * le volume traité est alors réputé égal au décompte facturé, ce qu'il était par construction
     * tant qu'un token de sortie pesait autant qu'un token d'entrée.
     */
    public UsageSnapshot(long usedTokens, long quotaTokens, long remainingTokens,
            LocalDate periodStart, LocalDate periodEnd) {
        this(usedTokens, quotaTokens, remainingTokens, usedTokens, periodStart, periodEnd);
    }
}
