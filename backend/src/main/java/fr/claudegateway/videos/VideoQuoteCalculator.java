package fr.claudegateway.videos;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * <b>Le calcul du devis d'une vidéo</b> (F-216 / SF-216-01) — pur, sans état ni accès : les mêmes entrées donnent
 * toujours le même devis.
 *
 * <p>Pour N épisodes de d minutes, c caractères de narration par épisode, r retouches prévues et t le coût d'un tour :</p>
 * <ul>
 *   <li><b>voix</b> d'un épisode {@code v = c × tarif/1000 + d × tarif_min} (tarifs de {@code voix_off}) ; basse N·v,
 *       haute N·v·(1 + r·fraction) ;</li>
 *   <li><b>agent</b> : tours bas N·(d·tpm_bas + r·tpr_bas) (+ plan de série), hauts idem ; × t ;</li>
 *   <li><b>retouche</b> : voix basse 0, haute r·fraction·v ; tours r·tpr ;</li>
 *   <li><b>rendu</b> : 0 $ (le poste) ; temps ⌈N·d·facteur⌉ minutes.</li>
 * </ul>
 * <p>Le total bas est arrondi au cent inférieur, le haut au cent supérieur : la fourchette ne se resserre jamais par
 * l'arrondi. Une borne égale à l'autre (pas de retouche : voix fixe) est arrondie au plus proche, pour s'afficher en
 * une seule valeur.</p>
 */
public final class VideoQuoteCalculator {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private VideoQuoteCalculator() {
    }

    /**
     * Ce qui est chiffré.
     *
     * @param kind            genre du devis
     * @param episodes        nombre d'épisodes (1 hors série)
     * @param durationMin     durée visée d'un épisode, en minutes
     * @param charsPerEpisode caractères de narration d'un épisode
     * @param retouches       retouches prévues (par épisode)
     */
    public record Input(VideoQuoteKind kind, int episodes, BigDecimal durationMin, long charsPerEpisode,
            int retouches) {
    }

    /** Les tarifs et le coût d'un tour retenus pour ce devis. */
    public record Rates(BigDecimal voiceUsdPerThousandChars, BigDecimal transcriptionUsdPerMinute,
            BigDecimal turnCostUsd) {
    }

    /** Le devis chiffré. Montants en dollars ; tours à deux décimales. */
    public record Result(Input input, BigDecimal voiceLow, BigDecimal voiceHigh, BigDecimal turnsLow,
            BigDecimal turnsHigh, BigDecimal agentLow, BigDecimal agentHigh, BigDecimal low, BigDecimal high,
            BigDecimal totalMinutes, int renderMinutes) {
    }

    /** Chiffre le devis. */
    public static Result compute(Input input, Rates rates, VideoCostProperties coefficients) {
        BigDecimal n = BigDecimal.valueOf(input.episodes());
        BigDecimal d = input.durationMin();
        BigDecimal r = BigDecimal.valueOf(input.retouches());
        BigDecimal voiceOne = rates.voiceUsdPerThousandChars().multiply(BigDecimal.valueOf(input.charsPerEpisode()))
                .divide(THOUSAND, 8, RoundingMode.HALF_UP)
                .add(rates.transcriptionUsdPerMinute().multiply(d));
        BigDecimal redone = r.multiply(coefficients.retoucheVoiceFraction());

        BigDecimal voiceLow;
        BigDecimal voiceHigh;
        BigDecimal turnsLow;
        BigDecimal turnsHigh;
        if (input.kind() == VideoQuoteKind.RETOUCHE) {
            voiceLow = BigDecimal.ZERO;
            voiceHigh = voiceOne.multiply(redone);
            turnsLow = r.multiply(coefficients.turnsPerRetoucheLow());
            turnsHigh = r.multiply(coefficients.turnsPerRetoucheHigh());
        } else {
            voiceLow = voiceOne.multiply(n);
            voiceHigh = voiceLow.multiply(BigDecimal.ONE.add(redone));
            turnsLow = n.multiply(d.multiply(coefficients.turnsPerMinuteLow())
                    .add(r.multiply(coefficients.turnsPerRetoucheLow())));
            turnsHigh = n.multiply(d.multiply(coefficients.turnsPerMinuteHigh())
                    .add(r.multiply(coefficients.turnsPerRetoucheHigh())));
            if (input.kind() == VideoQuoteKind.SERIES) {
                turnsLow = turnsLow.add(coefficients.seriesPlanningTurnsLow());
                turnsHigh = turnsHigh.add(coefficients.seriesPlanningTurnsHigh());
            }
        }
        BigDecimal agentLow = turnsLow.multiply(rates.turnCostUsd());
        BigDecimal agentHigh = turnsHigh.multiply(rates.turnCostUsd());
        BigDecimal totalMinutes = input.kind() == VideoQuoteKind.RETOUCHE ? d : n.multiply(d);
        int renderMinutes = totalMinutes.multiply(coefficients.renderMinutesPerVideoMinute())
                .setScale(0, RoundingMode.CEILING).intValueExact();
        BigDecimal low = voiceLow.add(agentLow);
        BigDecimal high = voiceHigh.add(agentHigh);
        return new Result(input,
                down(voiceLow, voiceHigh), up(voiceLow, voiceHigh),
                turnsLow.setScale(2, RoundingMode.HALF_UP), turnsHigh.setScale(2, RoundingMode.HALF_UP),
                down(agentLow, agentHigh), up(agentLow, agentHigh),
                down(low, high), up(low, high),
                totalMinutes.stripTrailingZeros(), renderMinutes);
    }

    /** La borne basse au cent inférieur — au cent le plus proche si la fourchette est un point (une seule valeur). */
    private static BigDecimal down(BigDecimal low, BigDecimal high) {
        return low.setScale(2, low.compareTo(high) == 0 ? RoundingMode.HALF_UP : RoundingMode.FLOOR);
    }

    /** La borne haute au cent supérieur — au cent le plus proche si la fourchette est un point. */
    private static BigDecimal up(BigDecimal low, BigDecimal high) {
        return high.setScale(2, low.compareTo(high) == 0 ? RoundingMode.HALF_UP : RoundingMode.CEILING);
    }
}
