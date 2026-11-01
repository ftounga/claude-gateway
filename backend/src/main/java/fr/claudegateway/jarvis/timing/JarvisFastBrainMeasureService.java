package fr.claudegateway.jarvis.timing;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import fr.claudegateway.jarvis.fastbrain.JarvisFastBrainService;

/**
 * <b>La mesure du cerveau rapide</b> (F-235 / SF-235-07, D14) — ce que le PO lit à J+7 / J+14 : premier son par
 * cerveau, taux de délégation, délégations d'office par raison, corrections, part des réponses creuses ou répétées
 * (avant retrait : la qualité du modèle ; après retrait, rien de creux n'est dit — le retrait est systématique), coût
 * en euros et tours Opus évités. Sur les tours <b>du compte appelant</b> seulement ; aucun contenu.
 */
@Service
public class JarvisFastBrainMeasureService {

    static final int MAX_DAYS = 90;

    private final JarvisTurnTimingRepository repository;
    private final Clock clock;
    private JarvisFastBrainService fastBrain;

    public JarvisFastBrainMeasureService(JarvisTurnTimingRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setFastBrain(JarvisFastBrainService fastBrain) {
        this.fastBrain = fastBrain;
    }

    /** La mesure des {@code days} derniers jours, sous {@code user_id}. */
    public Measure measure(UUID userId, int days) {
        int window = Math.max(1, Math.min(days, MAX_DAYS));
        List<JarvisTurnTiming> rows = repository.findByUserIdAndCreatedAtGreaterThanEqual(userId,
                OffsetDateTime.now(clock).minusDays(window));
        List<JarvisTurnTiming> fast = rows.stream().filter(r -> "FAST".equals(r.getBrain())).toList();
        List<JarvisTurnTiming> delegated = rows.stream().filter(r -> "FAST_DELEGATED".equals(r.getBrain())).toList();
        List<JarvisTurnTiming> opus = rows.stream()
                .filter(r -> r.getBrain() == null || "OPUS".equals(r.getBrain())).toList();

        Map<String, JarvisLatencyService.Step> firstSound = new LinkedHashMap<>();
        firstSound.put("FAST", step("firstSound", fast, JarvisTurnTiming::getFirstSoundMs));
        firstSound.put("FAST_DELEGATED", step("firstSound", delegated, JarvisTurnTiming::getFirstSoundMs));
        firstSound.put("OPUS", step("firstSound", opus, JarvisTurnTiming::getFirstSoundMs));

        Map<String, Long> automatic = new LinkedHashMap<>();
        for (JarvisTurnTiming r : delegated) {
            if (r.getDelegationReason() != null) {
                automatic.merge(r.getDelegationReason(), 1L, Long::sum);
            }
        }
        long fastExchanges = fast.size() + delegated.size();
        long corrections = rows.stream().filter(JarvisTurnTiming::isCorrection).count();
        long hollowOrRepeated = fast.stream().filter(r -> r.getFillersRemoved() + r.getRepeatsRemoved() > 0).count();
        BigDecimal costEur = null;
        try {
            costEur = fastBrain == null ? null : fastBrain.monthSpentEur(userId);
        } catch (RuntimeException e) {
            costEur = null;
        }
        return new Measure(window, rows.size(), fast.size(), delegated.size(), opus.size(), firstSound,
                step("fastFirstToken", rows, JarvisTurnTiming::getFastFirstTokenMs),
                ratio(delegated.size(), fastExchanges), automatic,
                delegated.stream().filter(JarvisTurnTiming::isForcedDelegation).count(),
                corrections, ratio(corrections, fast.size()),
                hollowOrRepeated, ratio(hollowOrRepeated, fast.size()),
                rows.stream().mapToLong(JarvisTurnTiming::getFillersRemoved).sum(),
                rows.stream().mapToLong(JarvisTurnTiming::getRepeatsRemoved).sum(),
                rows.stream().mapToLong(JarvisTurnTiming::getGroundingTrips).sum(),
                rows.stream().mapToLong(JarvisTurnTiming::getImitationsRemoved).sum(),
                costEur, fast.size());
    }

    private static JarvisLatencyService.Step step(String name, List<JarvisTurnTiming> rows,
            Function<JarvisTurnTiming, Integer> read) {
        return JarvisLatencyService.step(name, rows.stream().map(read).toList());
    }

    /** Part arrondie à 3 décimales ; {@code null} sans dénominateur. */
    static Double ratio(long part, long whole) {
        return whole <= 0 ? null : Math.round(part * 1000.0 / whole) / 1000.0;
    }

    /**
     * La mesure rendue. {@code hollowOrRepeatedShare} : part des réponses rapides dont au moins une phrase creuse ou
     * répétée a été retirée (avant retrait) ; ce que le PO entend en contient zéro par construction.
     */
    public record Measure(int days, int turns, int fastAnswers, int fastDelegated, int opusTurns,
            Map<String, JarvisLatencyService.Step> firstSoundByBrain, JarvisLatencyService.Step fastFirstToken,
            Double delegationRate, Map<String, Long> automaticDelegations, long forcedDelegations,
            long corrections, Double correctionRate, long hollowOrRepeatedAnswers, Double hollowOrRepeatedShare,
            long fillersRemoved, long repeatsRemoved, long groundingTrips, long imitationsRemoved,
            BigDecimal monthCostEur, long opusTurnsAvoided) {
    }
}
