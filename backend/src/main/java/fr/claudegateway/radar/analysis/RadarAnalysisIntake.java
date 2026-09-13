package fr.claudegateway.radar.analysis;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.radar.InvalidRadarInputException;
import fr.claudegateway.radar.RadarNotFoundException;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarStateConflictException;
import fr.claudegateway.radar.RadarSync;
import fr.claudegateway.radar.RadarSyncRepository;
import fr.claudegateway.radar.RadarSyncStatus;

/**
 * <b>La porte d'entrée de l'analyse</b> (F-101 / SF-101-01) : la collecte (F-100) y dépose ses lots.
 *
 * <p>Idempotente par {@code batchKey} dans le poste : une collecte reprise redépose ses lots sans rien
 * dupliquer ; un lot échoué ou expiré redéposé repart, avec son nouveau texte. Rien d'autre n'est fait
 * ici : aucun appel au modèle, aucune écriture au registre — l'analyse est une tâche de fond.</p>
 */
@Service
public class RadarAnalysisIntake {

    private final RadarAnalysisBatchRepository batches;
    private final RadarSyncRepository syncs;
    private final RadarAnalysisProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RadarAnalysisIntake(RadarAnalysisBatchRepository batches, RadarSyncRepository syncs,
            RadarAnalysisProperties properties, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.batches = batches;
        this.syncs = syncs;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Ce que la file a fait du lot. */
    public record IntakeReceipt(UUID batchId, RadarAnalysisBatchStatus status, boolean duplicate) {
    }

    /**
     * Dépose un lot.
     *
     * @throws InvalidRadarInputException  lot hors contrat
     * @throws RadarNotFoundException      synchro inconnue ou d'un autre poste
     * @throws RadarStateConflictException synchro annulée
     */
    public IntakeReceipt submit(RadarScope scope, UUID syncId, RadarExchangeBatch batch) {
        if (batch == null) {
            throw new InvalidRadarInputException("Lot absent.");
        }
        RadarExchangeBatch clean = batch.normalized();
        String payload = serialize(clean);
        try {
            return transactions.execute(status -> store(scope, syncId, clean, payload));
        } catch (DataIntegrityViolationException race) {
            // Deux dépôts du même lot au même instant : l'index d'unicité a tranché, on rend le gagnant.
            return transactions.execute(status -> batches
                    .findByUserIdAndHostIdAndBatchKey(scope.userId(), scope.hostId(), clean.batchKey())
                    .map(existing -> new IntakeReceipt(existing.getId(), existing.getStatus(), true))
                    .orElseThrow(() -> race));
        }
    }

    private IntakeReceipt store(RadarScope scope, UUID syncId, RadarExchangeBatch clean, String payload) {
        RadarSync sync = syncId == null ? null
                : syncs.findByIdAndUserIdAndHostId(syncId, scope.userId(), scope.hostId()).orElse(null);
        if (sync == null) {
            throw new RadarNotFoundException("Synchro introuvable.");
        }
        if (sync.getStatus() == RadarSyncStatus.CANCELLED) {
            throw new RadarStateConflictException("Synchro annulée : ses lots ne sont pas analysés.");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        var existing = batches.findByUserIdAndHostIdAndBatchKey(scope.userId(), scope.hostId(), clean.batchKey());
        if (existing.isPresent()) {
            RadarAnalysisBatch known = existing.get();
            if (known.getStatus() != RadarAnalysisBatchStatus.FAILED
                    && known.getStatus() != RadarAnalysisBatchStatus.EXPIRED) {
                return new IntakeReceipt(known.getId(), known.getStatus(), true);
            }
            // Échoué ou expiré : la collecte le redonne, il repart avec son nouveau texte.
            known.setSyncId(sync.getId());
            known.setStatus(RadarAnalysisBatchStatus.PENDING);
            known.setAttempts(0);
            known.setNextAttemptAt(null);
            known.setClaimedAt(null);
            known.setFailureCode(null);
            known.setPayload(payload);
            known.setRawDeletedAt(null);
            known.setExchangesCount(clean.exchanges().size());
            known.setMessagesCount(clean.messageCount());
            known.setReceivedAt(now);
            known.setExpiresAt(now.plus(properties.rawRetention()));
            return new IntakeReceipt(batches.save(known).getId(), RadarAnalysisBatchStatus.PENDING, false);
        }
        RadarAnalysisBatch saved = batches.saveAndFlush(RadarAnalysisBatch.builder()
                .userId(scope.userId()).hostId(scope.hostId()).syncId(sync.getId())
                .batchKey(clean.batchKey()).status(RadarAnalysisBatchStatus.PENDING)
                .payload(payload).exchangesCount(clean.exchanges().size()).messagesCount(clean.messageCount())
                .receivedAt(now).expiresAt(now.plus(properties.rawRetention()))
                .build());
        return new IntakeReceipt(saved.getId(), saved.getStatus(), false);
    }

    private String serialize(RadarExchangeBatch batch) {
        try {
            return objectMapper.writeValueAsString(batch);
        } catch (JsonProcessingException ex) {
            throw new InvalidRadarInputException("Lot illisible.");
        }
    }
}
