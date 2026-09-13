package fr.claudegateway.radar.analysis;

import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.radar.RadarScope;

/** Le compte rendu de l'analyse, par synchro (F-101). Toute lecture porte {@code user_id} et {@code host_id}. */
@Service
@Transactional(readOnly = true)
public class RadarAnalysisReport {

    private final RadarAnalysisBatchRepository batches;

    public RadarAnalysisReport(RadarAnalysisBatchRepository batches) {
        this.batches = batches;
    }

    /** L'analyse de chaque synchro désignée ; une synchro sans lot a une vue à zéro. */
    public Map<UUID, RadarSyncAnalysisView> bySync(RadarScope scope, Collection<UUID> syncIds) {
        Map<UUID, RadarSyncAnalysisView> views = new HashMap<>();
        if (syncIds == null || syncIds.isEmpty()) {
            return views;
        }
        Map<UUID, List<RadarAnalysisSyncCount>> counts = new HashMap<>();
        for (RadarAnalysisSyncCount count : batches.countBySync(scope.userId(), scope.hostId(), syncIds)) {
            counts.computeIfAbsent(count.syncId(), k -> new java.util.ArrayList<>()).add(count);
        }
        for (UUID syncId : syncIds) {
            views.put(syncId, view(counts.getOrDefault(syncId, List.of())));
        }
        return views;
    }

    static RadarSyncAnalysisView view(List<RadarAnalysisSyncCount> counts) {
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
        return new RadarSyncAnalysisView(byStatus, exchanges, messages, retained, attached, created, tokens);
    }
}
