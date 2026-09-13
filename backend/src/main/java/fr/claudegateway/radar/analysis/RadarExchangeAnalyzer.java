package fr.claudegateway.radar.analysis;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.teams.TeamsAccessService;

/**
 * <b>L'analyse d'un lot</b> (F-101) : le tri, puis l'extraction sur les seuls échanges retenus.
 *
 * <p>Déclaré, il active la file (SF-101-01). Il ne fait rien lui-même que la file ne sache reprendre :
 * chaque échec rend un {@code RETRY} avec sa consommation, et les écritures ne sont rendues qu'une fois
 * la sortie entièrement vérifiée — la file les joue dans la transaction qui efface le brut.</p>
 */
@Component
public class RadarExchangeAnalyzer implements RadarBatchAnalyzer {

    static final String NO_ENTITLEMENT = "NO_ENTITLEMENT";
    public static final String RESERVE_EXHAUSTED = "RESERVE_EXHAUSTED";

    private final TeamsAccessService teamsAccess;
    private final RadarTriage triage;
    private final RadarExtractor extractor;
    private final RadarRegistrySnapshot snapshot;
    private final RadarExtractionWriter writer;
    private final RadarReserve reserve;
    private final Clock clock;

    public RadarExchangeAnalyzer(TeamsAccessService teamsAccess, RadarTriage triage, RadarExtractor extractor,
            RadarRegistrySnapshot snapshot, RadarExtractionWriter writer, RadarReserve reserve, Clock clock) {
        this.teamsAccess = teamsAccess;
        this.triage = triage;
        this.extractor = extractor;
        this.snapshot = snapshot;
        this.writer = writer;
        this.reserve = reserve;
        this.clock = clock;
    }

    @Override
    public RadarAnalysisOutcome analyze(RadarScope scope, UUID syncId, UUID batchId, RadarExchangeBatch batch) {
        if (!teamsAccess.hasAccess(scope.userId())) {
            // Droit provisoire du Radar (celui de Teams, comme F-99) : sans lui, rien n'est lu ni dépensé.
            return RadarAnalysisOutcome.defer(RadarAnalysisTokens.NONE, NO_ENTITLEMENT, null);
        }
        RadarReserve.Availability before = reserve.available(scope, syncId, OffsetDateTime.now(clock));
        if (before.exhausted()) {
            // L'arrêt propre (cadrage §7) : aucun appel, le lot garde son texte et reprendra.
            return RadarAnalysisOutcome.defer(RadarAnalysisTokens.NONE, RESERVE_EXHAUSTED, before.retryAt());
        }
        RadarTriageResult sorted = triage.triage(scope.userId(), batch);
        if (!sorted.lisible()) {
            return RadarAnalysisOutcome.retry(sorted.tokens(), sorted.code());
        }
        if (sorted.retained().isEmpty()) {
            return RadarAnalysisOutcome.done(sorted.tokens(), 0, 0, 0, null);
        }
        if (before.remaining() - sorted.tokens().total() <= 0) {
            // Le tri a épuisé la réserve : pas d'extraction, rien n'est écrit, le tri est compté.
            return RadarAnalysisOutcome.defer(sorted.tokens(), RESERVE_EXHAUSTED, before.retryAt());
        }
        List<RadarExchangeBatch.Exchange> retained = new ArrayList<>();
        for (int index : sorted.retained()) {
            retained.add(batch.exchanges().get(index));
        }
        RadarExtractionContext context = RadarExtractionContext.build(snapshot.read(scope), retained);
        RadarExtractor.Result extracted = extractor.extract(scope.userId(), context);
        RadarAnalysisTokens tokens = sorted.tokens().plus(extracted.tokens());
        if (!extracted.lisible()) {
            return RadarAnalysisOutcome.retry(tokens, extracted.code());
        }
        RadarExtraction extraction = extracted.extraction();
        return RadarAnalysisOutcome.done(tokens, retained.size(), extraction.attachedCount(),
                extraction.createdCount(), () -> writer.write(scope, extraction));
    }
}
