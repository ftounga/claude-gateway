package fr.claudegateway.radar.sync;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.radar.RadarNotFoundException;
import fr.claudegateway.radar.RadarRegistry;
import fr.claudegateway.radar.RadarRunnerUnavailableException;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarStateConflictException;
import fr.claudegateway.radar.RadarSync;
import fr.claudegateway.radar.RadarSyncRepository;
import fr.claudegateway.radar.RadarSyncRunningException;
import fr.claudegateway.radar.RadarSyncStatus;
import fr.claudegateway.radar.RadarSyncTrigger;
import fr.claudegateway.radar.RadarTeamsDisabledException;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;

/**
 * <b>Démarrer une synchro</b> (F-100 / SF-100-02) — le chemin commun du créneau du soir, du rattrapage et
 * de « Synchroniser maintenant ».
 *
 * <ol>
 *   <li><b>Le verrou</b>, dans une transaction : la synchro est créée, puis le poste est pris par mise à
 *       jour conditionnelle. Un seul démarrage gagne, tous pods confondus ; le perdant annule tout.</li>
 *   <li><b>L'appel au runner</b>, hors transaction : il accepte et rend la main. Un refus close la synchro
 *       {@code FAILED} avec sa raison, et libère le poste — jamais une synchro qui « tourne » sans
 *       personne derrière.</li>
 * </ol>
 */
@Service
public class RadarSyncLauncher {

    public static final String COLLECT = "teams_radar_collect";
    public static final long COLLECT_TIMEOUT_MS = 20_000L;
    /** Curseurs transmis au plus : les plus récents. */
    static final int MAX_CURSORS = 2_000;

    private static final Logger log = LoggerFactory.getLogger(RadarSyncLauncher.class);

    private final RadarHostSettingsRepository settings;
    private final RadarSyncRepository syncs;
    private final RadarRegistry registry;
    private final RadarRunnerCalls calls;
    private final RadarSyncCursorRepository cursors;
    private final RadarThreadRuleRepository rules;
    private final RadarSyncProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public RadarSyncLauncher(RadarHostSettingsRepository settings, RadarSyncRepository syncs, RadarRegistry registry,
            RadarRunnerCalls calls, RadarSyncCursorRepository cursors, RadarThreadRuleRepository rules,
            RadarSyncProperties properties, ObjectMapper objectMapper, PlatformTransactionManager transactionManager,
            Clock clock) {
        this.settings = settings;
        this.syncs = syncs;
        this.registry = registry;
        this.calls = calls;
        this.cursors = cursors;
        this.rules = rules;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    /**
     * Démarre une synchro sur le poste.
     *
     * @param scheduledFor le créneau du soir, ou {@code null} pour « Synchroniser maintenant »
     * @throws RadarStateConflictException     Radar non activé sur ce poste
     * @throws RadarSyncRunningException       une synchro tient déjà le poste
     * @throws RadarRunnerUnavailableException le runner n'a pas accepté (la synchro est close FAILED)
     * @throws RadarTeamsDisabledException     volet Teams absent (la synchro est close FAILED)
     */
    public RadarSync start(RadarScope scope, RadarSyncTrigger trigger, OffsetDateTime scheduledFor) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<RadarSync> previous = syncs.findFirstByUserIdAndHostIdAndStatusInOrderByStartedAtDesc(
                scope.userId(), scope.hostId(), EnumSet.of(RadarSyncStatus.SUCCEEDED, RadarSyncStatus.PARTIAL));
        boolean firstSync = previous.isEmpty();
        // F-107 / SF-107-04 : la première synchro d'un client (trente jours) est hors réserve, une fois —
        // tant que le poste n'a aucune synchro réussie ou partielle.
        RadarSync sync = transactions.execute(status -> claim(scope, trigger, scheduledFor, now, firstSync));

        OffsetDateTime windowFrom = firstSync ? now.minus(properties.firstWindow())
                : previous.get().getStartedAt().minus(RadarSyncProperties.WINDOW_OVERLAP);

        ObjectNode input = objectMapper.createObjectNode();
        input.put("sync_id", sync.getId().toString());
        input.put("trigger", trigger.name());
        input.put("first_sync", firstSync);
        input.put("window_from", windowFrom.toInstant().toString());
        // SF-100-03 : où en est chaque fil, ce que l'utilisateur a écarté ou demandé de lire — du poste seul.
        var cursorArray = input.putArray("cursors");
        cursors.findByUserIdAndHostIdAndSourceOrderByCursorAtDesc(scope.userId(), scope.hostId(),
                RadarSyncCursor.SOURCE_TEAMS, org.springframework.data.domain.PageRequest.of(0, MAX_CURSORS))
                .forEach(cursor -> {
                    ObjectNode node = cursorArray.addObject();
                    node.put("ref", cursor.getConversationRef());
                    if (cursor.getKind() != null) {
                        node.put("kind", cursor.getKind());
                    }
                    node.put("at", cursor.getCursorAt().toInstant().toString());
                });
        // SF-100-05 : les enregistrements du dossier de dépôt déjà transcrits.
        var depotDone = input.putArray("depot_done");
        cursors.findByUserIdAndHostIdAndSourceOrderByCursorAtDesc(scope.userId(), scope.hostId(),
                RadarSyncCursor.SOURCE_DEPOT, org.springframework.data.domain.PageRequest.of(0, MAX_CURSORS))
                .forEach(cursor -> depotDone.add(cursor.getConversationRef()));
        var ignored = input.putArray("ignored");
        var readChannels = input.putArray("read_channels");
        rules.findByUserIdAndHostIdOrderByCreatedAtDesc(scope.userId(), scope.hostId()).forEach(rule -> {
            if (rule.getRule() == RadarThreadRule.Rule.IGNORE) {
                ignored.add(rule.getConversationRef());
            } else {
                readChannels.add(rule.getConversationRef());
            }
        });

        RunnerCallResult result = calls.call(scope, COLLECT, input, COLLECT_TIMEOUT_MS);
        if (!result.ok()) {
            RuntimeException failure = RunnerErrorCodes.UNSUPPORTED_TOOL.equals(result.errorCode())
                    ? new RadarTeamsDisabledException("Le volet Teams n'est pas actif sur ce poste (désactivé, ou "
                            + "runner trop ancien) : la synchro n'a pas pu partir.")
                    : new RadarRunnerUnavailableException("Poste injoignable : la synchro n'a pas pu partir.");
            closeRefused(scope, sync.getId(), result.errorCode() == null ? "RUNNER_ERROR" : result.errorCode(),
                    failure.getMessage());
            throw failure;
        }
        JsonNode answer = read(result.content());
        if (!answer.path("accepted").asBoolean(false)) {
            String reason = answer.path("reason").asText("REFUSED");
            String sentence = switch (reason) {
                case "BUSY" -> "Le poste termine déjà une autre synchro : réessayez dans quelques minutes.";
                case "NO_UPLINK" -> "Le runner de ce poste n'a pas de jeton : rien ne peut remonter. Réappairez-le.";
                case "TEAMS_DISABLED" -> "Le volet Teams est désactivé sur ce poste.";
                default -> "Le runner a refusé la synchro.";
            };
            closeRefused(scope, sync.getId(), reason, sentence);
            if ("TEAMS_DISABLED".equals(reason)) {
                throw new RadarTeamsDisabledException(sentence);
            }
            throw new RadarRunnerUnavailableException(sentence);
        }
        log.debug("Synchro Radar lancée (poste={}, synchro={}, déclencheur={})", scope.hostId(), sync.getId(), trigger);
        return sync;
    }

    private RadarSync claim(RadarScope scope, RadarSyncTrigger trigger, OffsetDateTime scheduledFor,
            OffsetDateTime now, boolean reserveExempt) {
        RadarHostSettings row = settings.findByUserIdAndHostId(scope.userId(), scope.hostId())
                .filter(RadarHostSettings::isEnabled)
                .orElseThrow(() -> new RadarStateConflictException("Le Radar n'est pas activé sur ce poste."));
        if (row.getRunningSyncId() != null) {
            closeIfStale(scope, row.getRunningSyncId(), now);
        }
        RadarSync sync = syncs.saveAndFlush(RadarSync.builder()
                .userId(scope.userId()).hostId(scope.hostId())
                .status(RadarSyncStatus.RUNNING).startedAt(now)
                .triggerKind(trigger).scheduledFor(scheduledFor).heartbeatAt(now)
                .reserveExempt(reserveExempt)
                .build());
        if (settings.claim(scope.userId(), scope.hostId(), sync.getId()) == 0) {
            UUID running = settings.findByUserIdAndHostId(scope.userId(), scope.hostId())
                    .map(RadarHostSettings::getRunningSyncId).orElse(null);
            throw new RadarSyncRunningException(running); // la transaction annule la synchro créée
        }
        return sync;
    }

    /**
     * Close une synchro <b>abandonnée</b> — plus de battement depuis {@code staleAfter} — et libère le poste.
     * Sans effet sur une synchro vivante ou déjà close (le verrou est alors simplement rendu).
     *
     * @return vrai si le poste a été libéré
     */
    public boolean closeIfStale(RadarScope scope, UUID syncId, OffsetDateTime now) {
        Optional<RadarSync> found = syncs.findByIdAndUserIdAndHostId(syncId, scope.userId(), scope.hostId());
        if (found.isPresent() && found.get().getStatus() == RadarSyncStatus.RUNNING) {
            OffsetDateTime last = found.get().getHeartbeatAt() == null ? found.get().getStartedAt()
                    : found.get().getHeartbeatAt();
            if (last.isAfter(now.minus(properties.staleAfter()))) {
                return false;
            }
            registry.finishSync(scope, syncId, RadarSyncStatus.FAILED, failure("INTERRUPTED",
                    "Synchro interrompue : le poste ne répond plus (veille, réseau ou runner arrêté). Elle reprendra "
                            + "où elle en était à la prochaine synchro."), 0);
        }
        return settings.release(scope.userId(), scope.hostId(), syncId) > 0;
    }

    private void closeRefused(RadarScope scope, UUID syncId, String code, String sentence) {
        transactions.executeWithoutResult(status -> {
            try {
                registry.finishSync(scope, syncId, RadarSyncStatus.FAILED, failure(code, sentence), 0);
            } catch (RadarNotFoundException e) {
                // purgée entre-temps : rien à clore
            }
            settings.release(scope.userId(), scope.hostId(), syncId);
        });
    }

    /** Une couverture d'échec nommé : le code et la phrase. */
    String failure(String code, String sentence) {
        ObjectNode coverage = objectMapper.createObjectNode();
        ObjectNode failure = coverage.putObject("failure");
        failure.put("code", code == null ? "" : code);
        failure.put("sentence", sentence == null ? "" : sentence);
        return coverage.toString();
    }

    private JsonNode read(String content) {
        try {
            JsonNode node = objectMapper.readTree(content == null ? "" : content);
            return node == null ? objectMapper.createObjectNode() : node;
        } catch (JsonProcessingException e) {
            return objectMapper.createObjectNode();
        }
    }
}
