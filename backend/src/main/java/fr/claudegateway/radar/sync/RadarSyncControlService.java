package fr.claudegateway.radar.sync;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.radar.InvalidRadarInputException;
import fr.claudegateway.radar.RadarNotFoundException;
import fr.claudegateway.radar.RadarRegistry;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarStateConflictException;
import fr.claudegateway.radar.RadarSync;
import fr.claudegateway.radar.RadarSyncRepository;
import fr.claudegateway.radar.RadarSyncStatus;

/**
 * <b>Piloter la synchro depuis la couverture</b> (F-100 / SF-100-04) : l'annuler, et dire d'un fil
 * <i>ignorer ce fil</i> ou <i>lire ce canal</i> — des corrections souveraines (cadrage §4.2), jamais remises
 * en cause par une synchro, annulables.
 */
@Service
public class RadarSyncControlService {

    public static final String CANCEL = "teams_radar_cancel";
    static final long CANCEL_TIMEOUT_MS = 5_000L;
    static final int MAX_LABEL_CHARS = 200;

    private final RadarSyncRepository syncs;
    private final RadarRegistry registry;
    private final RadarHostSettingsRepository settings;
    private final RadarThreadRuleRepository rules;
    private final RadarRunnerCalls calls;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;

    public RadarSyncControlService(RadarSyncRepository syncs, RadarRegistry registry, RadarHostSettingsRepository settings,
            RadarThreadRuleRepository rules, RadarRunnerCalls calls, ObjectMapper objectMapper,
            PlatformTransactionManager transactionManager) {
        this.syncs = syncs;
        this.registry = registry;
        this.settings = settings;
        this.rules = rules;
        this.calls = calls;
        this.objectMapper = objectMapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Ce que l'écran demande pour un fil. */
    public record RuleRequest(String conversationRef, String rule, String label) {
    }

    /** Une règle posée. */
    public record RuleView(UUID id, String conversationRef, RadarThreadRule.Rule rule, String label,
            java.time.OffsetDateTime createdAt) {

        static RuleView of(RadarThreadRule rule) {
            return new RuleView(rule.getId(), rule.getConversationRef(), rule.getRule(), rule.getLabel(), rule.getCreatedAt());
        }
    }

    /**
     * Annule une synchro en cours : elle est close {@code CANCELLED} (ce qui a été lu est conservé), le poste
     * est libéré, puis le runner est prévenu <b>au mieux</b> — la gateway fait foi, il s'arrêtera au premier
     * battement ou lot suivant.
     *
     * @throws RadarNotFoundException      synchro inconnue ou d'un autre poste
     * @throws RadarStateConflictException synchro déjà close
     */
    public RadarSync cancel(RadarScope scope, UUID syncId) {
        RadarSync sync = transactions.execute(status -> {
            RadarSync found = syncId == null ? null
                    : syncs.findByIdAndUserIdAndHostId(syncId, scope.userId(), scope.hostId()).orElse(null);
            if (found == null) {
                throw new RadarNotFoundException("Synchro introuvable.");
            }
            if (found.getStatus() != RadarSyncStatus.RUNNING) {
                throw new RadarStateConflictException("Cette synchro n'est plus en cours.");
            }
            ObjectNode coverage = readCoverage(found.getCoverage());
            coverage.put("cancelled", true);
            RadarSync closed = registry.finishSync(scope, found.getId(), RadarSyncStatus.CANCELLED, coverage.toString(), 0);
            settings.release(scope.userId(), scope.hostId(), found.getId());
            return closed;
        });
        try {
            ObjectNode input = objectMapper.createObjectNode();
            input.put("sync_id", sync.getId().toString());
            calls.call(scope, CANCEL, input, CANCEL_TIMEOUT_MS);
        } catch (RuntimeException e) {
            // au mieux : le runner s'arrêtera de lui-même au prochain échange (409)
        }
        return sync;
    }

    @Transactional(readOnly = true)
    public List<RuleView> rules(RadarScope scope) {
        return rules.findByUserIdAndHostIdOrderByCreatedAtDesc(scope.userId(), scope.hostId()).stream()
                .map(RuleView::of).toList();
    }

    /**
     * Pose une règle sur un fil. Idempotent : la même règle déjà posée est rendue telle quelle.
     *
     * @throws InvalidRadarInputException fil vide ou trop long, règle inconnue, libellé trop long
     */
    @Transactional
    public RuleView addRule(RadarScope scope, RuleRequest request) {
        if (request == null || request.conversationRef() == null || request.conversationRef().isBlank()
                || request.conversationRef().strip().length() > RadarSyncCursor.MAX_REF_CHARS) {
            throw new InvalidRadarInputException("Fil attendu (512 caractères au plus).");
        }
        RadarThreadRule.Rule rule;
        try {
            rule = RadarThreadRule.Rule.valueOf(request.rule() == null ? "" : request.rule().strip());
        } catch (IllegalArgumentException e) {
            throw new InvalidRadarInputException("Règle attendue : IGNORE ou READ_CHANNEL.");
        }
        String label = request.label() == null ? null : request.label().strip();
        if (label != null && label.length() > MAX_LABEL_CHARS) {
            throw new InvalidRadarInputException("Libellé de 200 caractères au plus.");
        }
        String ref = request.conversationRef().strip();
        Optional<RadarThreadRule> existing = rules.findByUserIdAndHostIdAndConversationRefAndRule(scope.userId(),
                scope.hostId(), ref, rule);
        if (existing.isPresent()) {
            return RuleView.of(existing.get());
        }
        return RuleView.of(rules.saveAndFlush(RadarThreadRule.builder().userId(scope.userId()).hostId(scope.hostId())
                .conversationRef(ref).rule(rule).label(label == null || label.isEmpty() ? null : label).build()));
    }

    /** Annule une règle. */
    @Transactional
    public void removeRule(RadarScope scope, UUID ruleId) {
        RadarThreadRule rule = ruleId == null ? null
                : rules.findByIdAndUserIdAndHostId(ruleId, scope.userId(), scope.hostId()).orElse(null);
        if (rule == null) {
            throw new RadarNotFoundException("Règle introuvable.");
        }
        rules.delete(rule);
    }

    private ObjectNode readCoverage(String json) {
        if (json != null && !json.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(json);
                if (node instanceof ObjectNode object) {
                    return object;
                }
            } catch (JsonProcessingException e) {
                // couverture illisible : on repart d'un objet vide
            }
        }
        return objectMapper.createObjectNode();
    }
}
