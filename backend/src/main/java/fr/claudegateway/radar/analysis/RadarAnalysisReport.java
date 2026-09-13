package fr.claudegateway.radar.analysis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.quota.BilledTokensCalculator;
import fr.claudegateway.radar.RadarCorrection;
import fr.claudegateway.radar.RadarCorrectionAction;
import fr.claudegateway.radar.RadarCorrectionRepository;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarSync;

/**
 * Le compte rendu de l'analyse, par synchro (F-101 / SF-101-01, SF-101-05) : progression, coût, arrêt
 * sur réserve, échecs de couverture et <b>qualité du rattachement</b> (cadrage §14 : « le point qui
 * décidera de l'adoption »). Toute lecture porte {@code user_id} et {@code host_id}.
 */
@Service
@Transactional(readOnly = true)
public class RadarAnalysisReport {

    /** Les corrections qui disent qu'un rattachement était faux : « même sujet », « pas le même sujet ». */
    static final Set<RadarCorrectionAction> ATTACHMENT_CORRECTIONS = Set.of(RadarCorrectionAction.MERGE,
            RadarCorrectionAction.SPLIT);

    private final RadarAnalysisBatchRepository batches;
    private final RadarCorrectionRepository corrections;
    private final BilledTokensCalculator calculator;

    public RadarAnalysisReport(RadarAnalysisBatchRepository batches, RadarCorrectionRepository corrections,
            BilledTokensCalculator calculator) {
        this.batches = batches;
        this.corrections = corrections;
        this.calculator = calculator;
    }

    /** L'analyse de chaque synchro désignée ; une synchro sans lot a une vue à zéro. */
    public Map<UUID, RadarSyncAnalysisView> bySync(RadarScope scope, List<RadarSync> syncs) {
        Map<UUID, RadarSyncAnalysisView> views = new HashMap<>();
        if (syncs == null || syncs.isEmpty()) {
            return views;
        }
        Map<UUID, List<RadarAnalysisSyncCount>> counts = new HashMap<>();
        for (RadarAnalysisSyncCount count : batches.countBySync(scope.userId(), scope.hostId(),
                syncs.stream().map(RadarSync::getId).toList())) {
            counts.computeIfAbsent(count.syncId(), k -> new ArrayList<>()).add(count);
        }
        Map<UUID, Long> reserveStops = new HashMap<>();
        for (Object[] row : batches.countDeferredBySync(scope.userId(), scope.hostId(),
                syncs.stream().map(RadarSync::getId).toList(), RadarExchangeAnalyzer.RESERVE_EXHAUSTED)) {
            reserveStops.put((UUID) row[0], (Long) row[1]);
        }
        List<OffsetDateTime> fixes = corrections.findByUserIdAndHostIdOrderByCreatedAtDesc(scope.userId(), scope.hostId())
                .stream()
                .filter(c -> ATTACHMENT_CORRECTIONS.contains(c.getAction()) && c.getUndoneAt() == null)
                .map(RadarCorrection::getCreatedAt)
                .toList();
        // La fenêtre d'une synchro court jusqu'au début de la synchro suivante du poste.
        List<RadarSync> ordered = syncs.stream().sorted(Comparator.comparing(RadarSync::getStartedAt)).toList();
        for (int i = 0; i < ordered.size(); i++) {
            RadarSync sync = ordered.get(i);
            OffsetDateTime from = sync.getStartedAt();
            OffsetDateTime to = i + 1 < ordered.size() ? ordered.get(i + 1).getStartedAt() : null;
            long fixed = fixes.stream().filter(at -> !at.isBefore(from) && (to == null || at.isBefore(to))).count();
            views.put(sync.getId(), view(counts.getOrDefault(sync.getId(), List.of()),
                    reserveStops.getOrDefault(sync.getId(), 0L) > 0, fixed));
        }
        return views;
    }

    RadarSyncAnalysisView view(List<RadarAnalysisSyncCount> counts, boolean stoppedOnReserve, long fixed) {
        Map<RadarAnalysisBatchStatus, Long> byStatus = new EnumMap<>(RadarAnalysisBatchStatus.class);
        for (RadarAnalysisBatchStatus status : RadarAnalysisBatchStatus.values()) {
            byStatus.put(status, 0L);
        }
        long exchanges = 0;
        long messages = 0;
        long retained = 0;
        long attached = 0;
        long created = 0;
        RadarAnalysisTokens tokens = RadarAnalysisTokens.NONE;
        for (RadarAnalysisSyncCount count : counts) {
            byStatus.merge(count.status(), RadarAnalysisSyncCount.n(count.batches()), Long::sum);
            exchanges += RadarAnalysisSyncCount.n(count.exchanges());
            messages += RadarAnalysisSyncCount.n(count.messages());
            retained += RadarAnalysisSyncCount.n(count.retained());
            attached += RadarAnalysisSyncCount.n(count.subjectsAttached());
            created += RadarAnalysisSyncCount.n(count.subjectsCreated());
            tokens = tokens.plus(count.tokens());
        }
        BigDecimal cost = calculator.costUsd(tokens.turnTokens()).setScale(6, RoundingMode.HALF_UP);
        long unread = byStatus.get(RadarAnalysisBatchStatus.FAILED) + byStatus.get(RadarAnalysisBatchStatus.EXPIRED);
        return new RadarSyncAnalysisView(byStatus, exchanges, messages, retained, attached, created, tokens, cost,
                stoppedOnReserve, unread, fixed, rate(fixed, attached + created));
    }

    /** Le taux, arrondi à 3 décimales ; {@code null} sans rattachement. */
    static Double rate(long corrections, long attachments) {
        if (attachments <= 0) {
            return null;
        }
        return BigDecimal.valueOf(corrections).divide(BigDecimal.valueOf(attachments), 3, RoundingMode.HALF_UP)
                .doubleValue();
    }
}
