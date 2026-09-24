package fr.claudegateway.diagnostic;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * <b>L'enquête d'une période</b> (F-156 / SF-156-02) : le dénominateur, puis ce que chaque capacité
 * de la carte y a fait.
 *
 * <p><b>Le dénominateur d'abord.</b> Un pourcentage sur une session se discute — la session était
 * peut-être atypique. Des <b>euros sur une semaine, sur tous les projets</b>, ne se discutent pas.
 * C'est ce qui rend le seuil d'impact vérifiable plutôt que déclaratif.</p>
 *
 * @param from         début de la période réellement observée
 * @param to           fin de la période
 * @param truncated    vrai quand la période demandée dépassait la borne et a été ramenée — le dire
 *                     fait partie du résultat, sinon le dénominateur mentirait
 * @param turns        tours de la période
 * @param projects     projets actifs
 * @param costEur      coût de la période
 * @param observations une par capacité de la carte, <b>y compris celles jamais déclenchées</b>
 */
public record ProductSurvey(
        OffsetDateTime from,
        OffsetDateTime to,
        boolean truncated,
        int turns,
        int projects,
        BigDecimal costEur,
        List<CapabilityObservation> observations) {

    /** Une enquête sans matière — ce n'est pas une erreur. */
    public static ProductSurvey empty(OffsetDateTime from, OffsetDateTime to) {
        return new ProductSurvey(from, to, false, 0, 0, BigDecimal.ZERO, List.of());
    }

    /** Vrai quand la période n'a rien à observer. */
    @com.fasterxml.jackson.annotation.JsonIgnore
    public boolean isEmpty() {
        return turns == 0 && projects == 0;
    }
}
