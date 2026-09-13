package fr.claudegateway.radar.sync;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.radar.InvalidRadarInputException;
import fr.claudegateway.radar.RadarNotFoundException;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarSync;
import fr.claudegateway.radar.RadarSyncRepository;
import fr.claudegateway.radar.RadarSyncStatus;
import fr.claudegateway.radar.analysis.RadarAnalysisIntake;
import fr.claudegateway.radar.analysis.RadarExchangeBatch;
import fr.claudegateway.runner.RunnerIdentity;

/**
 * <b>Les lots de la collecte</b> (F-100 / SF-100-03) : le runner dépose ce qu'il a lu dans la file d'analyse
 * de F-101, et les curseurs de ses fils avancent — <b>après</b> que le lot a été accepté, jamais avant, et
 * jamais en arrière.
 *
 * <p>Trois temps, trois transactions : vérifier que la synchro court (périmètre du jeton), déposer le lot
 * (idempotent, transaction de la file), avancer les curseurs et battre. Un lot refusé ne fait bouger aucun
 * curseur : le fil sera relu.</p>
 */
@Service
public class RadarSyncBatchService {

    static final int MAX_CURSORS = 200;
    static final Set<String> KINDS = Set.of("CONVERSATION", "CHANNEL", "MEETING", "RECORDING");

    private final RadarSyncRepository syncs;
    private final RadarSyncCursorRepository cursors;
    private final RadarAnalysisIntake intake;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RadarSyncBatchService(RadarSyncRepository syncs, RadarSyncCursorRepository cursors, RadarAnalysisIntake intake,
            ObjectMapper objectMapper, PlatformTransactionManager transactionManager, Clock clock) {
        this.syncs = syncs;
        this.cursors = cursors;
        this.intake = intake;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /** Ce que la gateway répond à un lot. */
    public record BatchReceipt(RadarSyncStatus status, UUID batchId, String batchStatus, boolean duplicate, int cursors) {
    }

    /** Un curseur lu et validé. */
    record CursorInput(String ref, String kind, OffsetDateTime at) {
    }

    /**
     * Dépose un lot et avance les curseurs.
     *
     * @param source {@code TEAMS} ou {@code DEPOT}
     * @throws RadarNotFoundException                    synchro inconnue ou d'un autre poste
     * @throws RadarSyncSessionService.SyncClosedException synchro close
     * @throws InvalidRadarInputException                 lot ou curseurs hors contrat
     */
    public BatchReceipt submit(RunnerIdentity identity, UUID syncId, JsonNode body, String source) {
        RadarScope scope = new RadarScope(identity.userId(), identity.hostId());
        List<CursorInput> parsed = parseCursors(body == null ? null : body.get("cursors"));
        RadarExchangeBatch batch = parseBatch(body == null ? null : body.get("batch"));

        transactions.executeWithoutResult(status -> running(scope, syncId));

        RadarAnalysisIntake.IntakeReceipt receipt = batch == null ? null : intake.submit(scope, syncId, batch);

        int advanced = transactions.execute(status -> {
            RadarSync sync = running(scope, syncId);
            OffsetDateTime now = OffsetDateTime.now(clock);
            sync.setHeartbeatAt(now);
            int count = 0;
            for (CursorInput cursor : parsed) {
                // SF-100-05 : un enregistrement du dossier de dépôt a son curseur sous la source DEPOT.
                String cursorSource = "RECORDING".equals(cursor.kind()) ? RadarSyncCursor.SOURCE_DEPOT : source;
                if (advance(scope, cursorSource, cursor)) {
                    count++;
                }
            }
            return count;
        });
        return new BatchReceipt(RadarSyncStatus.RUNNING, receipt == null ? null : receipt.batchId(),
                receipt == null ? null : receipt.status().name(), receipt != null && receipt.duplicate(), advanced);
    }

    private boolean advance(RadarScope scope, String source, CursorInput input) {
        RadarSyncCursor cursor = cursors.findByUserIdAndHostIdAndSourceAndConversationRef(scope.userId(), scope.hostId(),
                source, input.ref()).orElse(null);
        if (cursor == null) {
            // Un seul travail de synchro par poste (SF-100-02) : pas de dépôt concurrent sur le même fil. Si
            // cela arrivait malgré tout, l'index d'unicité refuserait, le dépôt échouerait et le fil serait relu.
            cursors.save(RadarSyncCursor.builder().userId(scope.userId()).hostId(scope.hostId())
                    .source(source).conversationRef(input.ref()).kind(input.kind()).cursorAt(input.at()).build());
            return true;
        }
        if (!input.at().isAfter(cursor.getCursorAt())) {
            return false; // un curseur ne recule jamais
        }
        cursor.setCursorAt(input.at());
        if (input.kind() != null) {
            cursor.setKind(input.kind());
        }
        return true;
    }

    private RadarSync running(RadarScope scope, UUID syncId) {
        if (syncId == null) {
            throw new RadarNotFoundException("Synchro introuvable.");
        }
        RadarSync sync = syncs.findByIdAndUserIdAndHostId(syncId, scope.userId(), scope.hostId())
                .orElseThrow(() -> new RadarNotFoundException("Synchro introuvable."));
        if (sync.getStatus() != RadarSyncStatus.RUNNING) {
            throw new RadarSyncSessionService.SyncClosedException(sync.getStatus());
        }
        return sync;
    }

    private RadarExchangeBatch parseBatch(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return objectMapper.treeToValue(node, RadarExchangeBatch.class);
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new InvalidRadarInputException("Lot illisible : forme attendue du contrat de la file d'analyse.");
        }
    }

    private List<CursorInput> parseCursors(JsonNode node) {
        List<CursorInput> parsed = new ArrayList<>();
        if (node == null || node.isNull()) {
            return parsed;
        }
        if (!node.isArray() || node.size() > MAX_CURSORS) {
            throw new InvalidRadarInputException("Curseurs attendus : une liste de " + MAX_CURSORS + " au plus.");
        }
        OffsetDateTime latest = OffsetDateTime.now(clock).plusHours(1);
        for (JsonNode entry : node) {
            String ref = entry.path("ref").asText("").strip();
            if (ref.isEmpty() || ref.length() > RadarSyncCursor.MAX_REF_CHARS) {
                throw new InvalidRadarInputException("Curseur sans fil, ou fil trop long.");
            }
            String kind = entry.path("kind").asText("").strip().toUpperCase(Locale.ROOT);
            OffsetDateTime at;
            try {
                at = OffsetDateTime.parse(entry.path("at").asText(""));
            } catch (DateTimeParseException e) {
                throw new InvalidRadarInputException("Curseur sans instant lisible.");
            }
            if (at.isAfter(latest)) {
                throw new InvalidRadarInputException("Curseur dans le futur.");
            }
            parsed.add(new CursorInput(ref, KINDS.contains(kind) ? kind : null, at));
        }
        return parsed;
    }
}
