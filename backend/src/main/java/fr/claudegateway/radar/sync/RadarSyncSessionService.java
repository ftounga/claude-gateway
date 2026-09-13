package fr.claudegateway.radar.sync;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.radar.InvalidRadarInputException;
import fr.claudegateway.radar.RadarNotFoundException;
import fr.claudegateway.radar.RadarRegistry;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarSync;
import fr.claudegateway.radar.RadarSyncRepository;
import fr.claudegateway.radar.RadarSyncStatus;
import fr.claudegateway.runner.RunnerIdentity;

/**
 * <b>Ce que le runner rend compte d'une synchro</b> (F-100 / SF-100-02) : son battement et sa fin.
 *
 * <p>Le périmètre vient <b>du jeton</b> ({@link RunnerIdentity} : compte et poste), jamais du corps ni du
 * chemin : une synchro d'un autre poste est « introuvable ». Une synchro close (annulée, abandonnée) répond
 * par son statut, et le runner s'arrête.</p>
 */
@Service
@Transactional
public class RadarSyncSessionService {

    static final int MAX_PHASE_CHARS = 64;
    static final int MAX_COVERAGE_CHARS = 16_000;

    private final RadarSyncRepository syncs;
    private final RadarRegistry registry;
    private final RadarHostSettingsRepository settings;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public RadarSyncSessionService(RadarSyncRepository syncs, RadarRegistry registry,
            RadarHostSettingsRepository settings, ObjectMapper objectMapper, Clock clock) {
        this.syncs = syncs;
        this.registry = registry;
        this.settings = settings;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** La synchro n'est plus en cours : le runner doit s'arrêter. */
    public static class SyncClosedException extends RuntimeException {

        private final RadarSyncStatus status;

        public SyncClosedException(RadarSyncStatus status) {
            super("Synchro close : " + status);
            this.status = status;
        }

        public RadarSyncStatus status() {
            return status;
        }
    }

    /** Le battement : où en est la collecte. */
    public RadarSync progress(RunnerIdentity identity, UUID syncId, JsonNode body) {
        RadarSync sync = running(identity, syncId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        sync.setHeartbeatAt(now);
        ObjectNode progress = objectMapper.createObjectNode();
        String phase = body == null ? "" : body.path("phase").asText("");
        progress.put("phase", phase.length() <= MAX_PHASE_CHARS ? phase : phase.substring(0, MAX_PHASE_CHARS));
        progress.put("done", Math.max(0, body == null ? 0 : body.path("done").asInt(0)));
        progress.put("total", Math.max(0, body == null ? 0 : body.path("total").asInt(0)));
        progress.put("at", now.toString());
        sync.setProgress(progress.toString());
        return sync;
    }

    /**
     * La fin : l'issue et la couverture ; le poste est libéré. La première fin gagne.
     *
     * @throws InvalidRadarInputException statut absent, {@code RUNNING} ou inconnu ; couverture trop grosse
     */
    public RadarSync finish(RunnerIdentity identity, UUID syncId, JsonNode body) {
        String raw = body == null ? "" : body.path("status").asText("").strip().toUpperCase(Locale.ROOT);
        RadarSyncStatus status;
        try {
            status = RadarSyncStatus.valueOf(raw);
        } catch (IllegalArgumentException e) {
            throw new InvalidRadarInputException("Issue attendue : SUCCEEDED, PARTIAL ou FAILED.");
        }
        if (status == RadarSyncStatus.RUNNING || status == RadarSyncStatus.CANCELLED) {
            throw new InvalidRadarInputException("Issue attendue : SUCCEEDED, PARTIAL ou FAILED.");
        }
        JsonNode coverage = body.path("coverage");
        String coverageJson = coverage.isObject() ? coverage.toString() : "{}";
        if (coverageJson.length() > MAX_COVERAGE_CHARS) {
            throw new InvalidRadarInputException("Couverture trop volumineuse.");
        }
        RadarSync sync = running(identity, syncId);
        RadarScope scope = new RadarScope(identity.userId(), identity.hostId());
        registry.finishSync(scope, sync.getId(), status, coverageJson, 0);
        sync.setHeartbeatAt(OffsetDateTime.now(clock));
        settings.release(identity.userId(), identity.hostId(), sync.getId());
        return sync;
    }

    private RadarSync running(RunnerIdentity identity, UUID syncId) {
        if (syncId == null) {
            throw new RadarNotFoundException("Synchro introuvable.");
        }
        RadarSync sync = syncs.findByIdAndUserIdAndHostId(syncId, identity.userId(), identity.hostId())
                .orElseThrow(() -> new RadarNotFoundException("Synchro introuvable."));
        if (sync.getStatus() != RadarSyncStatus.RUNNING) {
            throw new SyncClosedException(sync.getStatus());
        }
        return sync;
    }
}
