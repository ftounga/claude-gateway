package fr.claudegateway.radar.analysis;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarSyncRepository;
import fr.claudegateway.radar.RadarSyncStatus;

/**
 * <b>La file d'analyse</b> du Radar (F-101 / SF-101-01).
 *
 * <p>Trois règles, et chacune protège quelque chose :</p>
 * <ul>
 *   <li><b>Un poste à la fois.</b> Un bail par poste ({@link RadarAnalysisLease}) : l'ordre des lots
 *       compte, un sujet créé par un lot doit être connu du suivant, et deux pods ne doivent pas créer
 *       deux fois le même sujet.</li>
 *   <li><b>Le modèle hors transaction, les faits dans une seule.</b> L'analyseur est appelé sans
 *       transaction ouverte ; ses écritures et l'effacement du brut sont joués ensemble. Des faits sans
 *       effacement, ou un effacement sans faits, n'existent pas.</li>
 *   <li><b>Rien ne se perd en silence.</b> Une exception de l'analyseur vaut une nouvelle tentative ;
 *       au bout des tentatives, le lot est {@code FAILED} et garde son brut jusqu'à l'expiration.</li>
 * </ul>
 *
 * <p><b>Rien n'est journalisé du contenu</b> : ni texte, ni titre, ni auteur. Seuls des identifiants et
 * des codes courts.</p>
 */
@Service
public class RadarAnalysisQueue {

    private static final Logger log = LoggerFactory.getLogger(RadarAnalysisQueue.class);

    static final String CORRUPT_PAYLOAD = "CORRUPT_PAYLOAD";
    static final String ANALYZER_ERROR = "ANALYZER_ERROR";
    static final String WRITE_REJECTED = "WRITE_REJECTED";

    private final RadarAnalysisBatchRepository batches;
    private final RadarAnalysisLeaseRepository leases;
    private final RadarSyncRepository syncs;
    private final ObjectProvider<RadarBatchAnalyzer> analyzers;
    private final RadarAnalysisProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final String owner = "radar-" + UUID.randomUUID();

    public RadarAnalysisQueue(RadarAnalysisBatchRepository batches, RadarAnalysisLeaseRepository leases,
            RadarSyncRepository syncs, ObjectProvider<RadarBatchAnalyzer> analyzers,
            RadarAnalysisProperties properties, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.batches = batches;
        this.leases = leases;
        this.syncs = syncs;
        this.analyzers = analyzers;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Un passage : quelques postes, quelques lots chacun.
     *
     * @return le nombre de lots traités (quelle que soit leur issue)
     */
    public int runOnce() {
        RadarBatchAnalyzer analyzer = analyzers.getIfAvailable();
        if (analyzer == null) {
            return 0; // Pas d'analyseur : un lot pris serait un lot perdu.
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<RadarScope> scopes = batches.findClaimableScopes(now, staleBefore(now),
                PageRequest.of(0, properties.hostsPerRun()));
        int processed = 0;
        for (RadarScope scope : scopes) {
            processed += runHost(scope, analyzer);
        }
        return processed;
    }

    /**
     * Traite les lots prenables d'un poste, s'il obtient son bail.
     *
     * @return le nombre de lots traités ; 0 si le bail est tenu ailleurs
     */
    public int runHost(RadarScope scope, RadarBatchAnalyzer analyzer) {
        if (!acquire(scope)) {
            return 0;
        }
        int processed = 0;
        try {
            for (int i = 0; i < properties.batchesPerHost(); i++) {
                OffsetDateTime now = OffsetDateTime.now(clock);
                List<UUID> ids = batches.findClaimableIds(scope.userId(), scope.hostId(), now, staleBefore(now),
                        PageRequest.of(0, 1));
                if (ids.isEmpty()) {
                    break;
                }
                UUID id = ids.get(0);
                Integer claimed = transactions.execute(status ->
                        batches.claim(id, scope.userId(), scope.hostId(), now, staleBefore(now)));
                if (claimed == null || claimed == 0) {
                    break; // Pris ailleurs malgré le bail (bail échu) : on laisse la main.
                }
                renew(scope);
                process(scope, id, analyzer);
                processed++;
            }
        } finally {
            release(scope);
        }
        return processed;
    }

    /**
     * Efface le brut de tout lot arrivé au terme de sa rétention.
     *
     * @return le nombre de lots expirés sans avoir été analysés
     */
    public int expireRaw() {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Integer expired = transactions.execute(status -> {
            int count = batches.expireUnanalyzed(now);
            batches.expireRemainingRaw(now);
            return count;
        });
        return expired == null ? 0 : expired;
    }

    // ------------------------------------------------------------------------------ traitement

    private void process(RadarScope scope, UUID batchId, RadarBatchAnalyzer analyzer) {
        RadarAnalysisBatch loaded = transactions.execute(status ->
                batches.findByIdAndUserIdAndHostId(batchId, scope.userId(), scope.hostId()).orElse(null));
        if (loaded == null) {
            return;
        }
        // SF-100-08 : filet pour la course « un lot déposé juste après l'annulation ». Une synchro annulée ne
        // voit aucun de ses lots analysé : le lot est écarté, sans appel au modèle, sans réserve ni jeton.
        if (cancelled(scope, loaded.getSyncId())) {
            transactions.executeWithoutResult(status -> update(scope, batchId, b -> {
                b.setStatus(RadarAnalysisBatchStatus.DISCARDED);
                b.setFailureCode(null);
                b.setNextAttemptAt(null);
                b.deleteRaw(OffsetDateTime.now(clock));
            }, RadarAnalysisTokens.NONE));
            log.debug("Radar : lot {} écarté, sa synchro a été annulée", batchId);
            return;
        }
        RadarExchangeBatch batch = read(loaded.getPayload());
        if (batch == null) {
            transactions.executeWithoutResult(status -> update(scope, batchId, b -> {
                b.setStatus(RadarAnalysisBatchStatus.FAILED);
                b.setFailureCode(CORRUPT_PAYLOAD);
                b.deleteRaw(OffsetDateTime.now(clock));
            }, RadarAnalysisTokens.NONE));
            log.warn("Radar : lot {} illisible en file, abandonné", batchId);
            return;
        }
        RadarAnalysisOutcome outcome;
        try {
            outcome = analyzer.analyze(scope, loaded.getSyncId(), batchId, batch);
            if (outcome == null) {
                outcome = RadarAnalysisOutcome.retry(RadarAnalysisTokens.NONE, ANALYZER_ERROR);
            }
        } catch (RuntimeException ex) {
            log.warn("Radar : analyse du lot {} en échec ({})", batchId, ex.getClass().getSimpleName());
            outcome = RadarAnalysisOutcome.retry(RadarAnalysisTokens.NONE, ANALYZER_ERROR);
        }
        apply(scope, batchId, outcome, batch.downloadBlocked());
    }

    private void apply(RadarScope scope, UUID batchId, RadarAnalysisOutcome outcome, boolean downloadBlocked) {
        if (outcome.kind() == RadarAnalysisOutcome.Kind.DONE) {
            try {
                transactions.executeWithoutResult(status -> {
                    outcome.writer().write();
                    update(scope, batchId, b -> {
                        OffsetDateTime now = OffsetDateTime.now(clock);
                        b.setStatus(RadarAnalysisBatchStatus.DONE);
                        b.setAnalyzedAt(now);
                        b.setFailureCode(null);
                        b.setNextAttemptAt(null);
                        b.setRetainedCount(outcome.retainedCount());
                        b.setSubjectsAttached(outcome.subjectsAttached());
                        b.setSubjectsCreated(outcome.subjectsCreated());
                        b.deleteRaw(now);
                    }, outcome.tokens());
                });
                return;
            } catch (RuntimeException ex) {
                // Le registre a refusé une écriture : rien n'est écrit, le brut reste, on retentera.
                log.warn("Radar : écritures du lot {} refusées ({})", batchId, ex.getClass().getSimpleName());
                retry(scope, batchId, RadarAnalysisOutcome.retry(outcome.tokens(), WRITE_REJECTED), downloadBlocked);
                return;
            }
        }
        if (outcome.kind() == RadarAnalysisOutcome.Kind.DEFER) {
            transactions.executeWithoutResult(status -> update(scope, batchId, b -> {
                OffsetDateTime now = OffsetDateTime.now(clock);
                b.setStatus(RadarAnalysisBatchStatus.DEFERRED);
                b.setFailureCode(code(outcome.code()));
                b.setAttempts(Math.max(0, b.getAttempts() - 1)); // Un report n'est pas un échec.
                b.setNextAttemptAt(outcome.retryAt() != null && outcome.retryAt().isAfter(now)
                        ? outcome.retryAt() : now.plus(properties.deferDelay()));
            }, outcome.tokens()));
            return;
        }
        retry(scope, batchId, outcome, downloadBlocked);
    }

    private void retry(RadarScope scope, UUID batchId, RadarAnalysisOutcome outcome, boolean downloadBlocked) {
        transactions.executeWithoutResult(status -> update(scope, batchId, b -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            b.setFailureCode(code(outcome.code()));
            if (b.getAttempts() >= properties.maxAttempts()) {
                b.setStatus(RadarAnalysisBatchStatus.FAILED);
                b.setNextAttemptAt(null);
                if (downloadBlocked) {
                    // F-89 / SF-89-06 : une transcription au téléchargement bloqué n'attend pas l'expiration —
                    // abandonnée, son texte brut est effacé tout de suite.
                    b.deleteRaw(now);
                }
            } else {
                b.setStatus(RadarAnalysisBatchStatus.PENDING);
                b.setNextAttemptAt(now.plus(properties.backoff(b.getAttempts())));
            }
        }, outcome.tokens()));
    }

    /** Relit le lot, applique la mutation, cumule la consommation sur le lot et sur sa synchro. */
    private void update(RadarScope scope, UUID batchId, java.util.function.Consumer<RadarAnalysisBatch> mutation,
            RadarAnalysisTokens tokens) {
        RadarAnalysisBatch batch = batches.findByIdAndUserIdAndHostId(batchId, scope.userId(), scope.hostId())
                .orElse(null);
        if (batch == null) {
            return; // Purgé pendant l'analyse : rien à mettre à jour.
        }
        mutation.accept(batch);
        batch.addTokens(tokens);
        batches.saveAndFlush(batch);
        if (tokens != null && tokens.total() > 0) {
            syncs.addConsumedTokens(batch.getSyncId(), scope.userId(), scope.hostId(), tokens.total());
        }
    }

    /** Vrai si la synchro du lot a été annulée (SF-100-08). */
    private boolean cancelled(RadarScope scope, UUID syncId) {
        if (syncId == null) {
            return false;
        }
        return Boolean.TRUE.equals(transactions.execute(status ->
                syncs.findByIdAndUserIdAndHostId(syncId, scope.userId(), scope.hostId())
                        .map(sync -> sync.getStatus() == RadarSyncStatus.CANCELLED)
                        .orElse(false)));
    }

    private RadarExchangeBatch read(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(payload, RadarExchangeBatch.class).normalized();
        } catch (Exception ex) {
            return null;
        }
    }

    private static String code(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        String trimmed = code.strip();
        return trimmed.length() <= 32 ? trimmed : trimmed.substring(0, 32);
    }

    // ----------------------------------------------------------------------------------- bail

    private OffsetDateTime staleBefore(OffsetDateTime now) {
        return now.minus(properties.leaseDuration());
    }

    private boolean acquire(RadarScope scope) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime until = now.plus(properties.leaseDuration());
        Integer taken = transactions.execute(status ->
                leases.acquire(scope.userId(), scope.hostId(), owner, now, until));
        if (taken != null && taken > 0) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(transactions.execute(status -> {
                if (leases.existsByUserIdAndHostId(scope.userId(), scope.hostId())) {
                    return false; // Tenu ailleurs, et pas échu.
                }
                leases.saveAndFlush(RadarAnalysisLease.builder().userId(scope.userId()).hostId(scope.hostId())
                        .owner(owner).leasedUntil(until).build());
                return true;
            }));
        } catch (DataIntegrityViolationException race) {
            return false; // Un autre pod a créé le bail au même instant : il est à lui.
        }
    }

    private void renew(RadarScope scope) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        transactions.execute(status ->
                leases.acquire(scope.userId(), scope.hostId(), owner, now, now.plus(properties.leaseDuration())));
    }

    private void release(RadarScope scope) {
        try {
            transactions.execute(status ->
                    leases.release(scope.userId(), scope.hostId(), owner, OffsetDateTime.now(clock).minusSeconds(1)));
        } catch (RuntimeException ex) {
            log.debug("Radar : bail non rendu ({}), il échoira seul", ex.getClass().getSimpleName());
        }
    }
}
