package fr.claudegateway.runner.diag;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.channel.RunnerDiagFrameEvent;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * Journal de diagnostic du runner (F-132 / SF-132-02) : reçoit la trame {@code runner_diag}
 * (SF-132-01), la range dans un <b>anneau borné par poste</b> avec <b>TTL 7 j</b>, et la relit pour
 * son propriétaire.
 *
 * <p><b>Non bloquant par construction</b> (comme {@code runner_audit}) : l'ingestion absorbe toute
 * erreur — un journal indisponible ne doit jamais couper une liaison runner saine.</p>
 *
 * <p><b>Isolation</b> : à l'écriture, {@code user_id}/{@code host_id} viennent de la <b>session</b>
 * runner (portés par {@link RunnerDiagFrameEvent}), jamais d'un champ du message ; à la lecture,
 * {@link RunnerHostService#requireOwned} garde l'accès (404 sinon).</p>
 */
@Service
public class RunnerDiagService {

    /** Anneau : nombre de lignes retenues par poste au-delà duquel les plus anciennes sont supprimées. */
    public static final int RING_CAPACITY = 2_000;
    /** Rétention (défaut confirmé PO). */
    public static final int TTL_DAYS = 7;
    /** Nombre de lignes rendues par défaut. */
    public static final int DEFAULT_LIMIT = 100;
    /** Plafond de lecture : un journal se consulte, il ne se déverse pas. */
    public static final int MAX_LIMIT = 500;

    private static final Logger log = LoggerFactory.getLogger(RunnerDiagService.class);

    private static final int MAX_LEVEL_CHARS = 8;
    private static final int MAX_CATEGORY_CHARS = 32;
    private static final int MAX_CODE_CHARS = 64;
    private static final int MAX_MESSAGE_CHARS = 500;
    private static final int MAX_FIELDS_CHARS = 2_000;
    /** Garde-fou : un lot ne persiste jamais plus que cela (une trame batchée en émet ~200). */
    private static final int MAX_EVENTS_PER_FRAME = 500;

    private final RunnerDiagEventRepository repository;
    private final RunnerHostService hostService;
    private final ObjectMapper objectMapper;

    public RunnerDiagService(RunnerDiagEventRepository repository, RunnerHostService hostService,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.hostService = hostService;
        this.objectMapper = objectMapper;
    }

    /** Reçoit une trame {@code runner_diag} publiée par le dispatcher (best-effort). */
    @EventListener
    public void onFrame(RunnerDiagFrameEvent event) {
        ingest(event.userId(), event.hostId(), event.frame());
    }

    /**
     * Persiste les événements d'une trame {@code runner_diag} puis taille l'anneau du poste.
     * Best-effort : une trame mal formée ne persiste rien et ne lève jamais.
     */
    @Transactional
    public void ingest(UUID userId, UUID hostId, JsonNode frame) {
        if (userId == null || hostId == null || frame == null) {
            return;
        }
        try {
            JsonNode events = frame.path("events");
            if (!events.isArray() || events.isEmpty()) {
                return;
            }
            List<RunnerDiagEventEntity> rows = new ArrayList<>();
            for (JsonNode node : events) {
                if (rows.size() >= MAX_EVENTS_PER_FRAME) {
                    break;
                }
                RunnerDiagEventEntity row = toEntity(userId, hostId, node);
                if (row != null) {
                    rows.add(row);
                }
            }
            if (rows.isEmpty()) {
                return;
            }
            repository.saveAll(rows);
            trimRing(userId, hostId);
        } catch (RuntimeException e) {
            // Le diagnostic ne casse jamais la liaison runner (comme runner_audit).
            log.warn("Ingestion runner_diag impossible (poste={}) : {}", hostId, e.getMessage());
        }
    }

    /** Derniers événements d'un poste <b>possédé</b>, filtrés par niveau minimum et fenêtre temporelle. */
    @Transactional(readOnly = true)
    public List<RunnerDiagEventEntity> list(UUID userId, UUID hostId, RunnerDiagLevel minLevel,
            OffsetDateTime since, OffsetDateTime until, Integer limit) {
        hostService.requireOwned(userId, hostId); // 404 si non possédé — isolation d'abord
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        RunnerDiagLevel floor = minLevel == null ? RunnerDiagLevel.DEBUG : minLevel;
        OffsetDateTime from = since == null ? OffsetDateTime.now().minusYears(100) : since;
        OffsetDateTime to = until == null ? OffsetDateTime.now().plusYears(100) : until;
        return repository.findWindow(userId, hostId, floor.namesAtLeast(), from, to,
                PageRequest.of(0, size));
    }

    /** Purge TTL (worker planifié) : supprime les lignes plus vieilles que la coupure. */
    @Transactional
    public int purgeExpired(OffsetDateTime now) {
        return repository.deleteOlderThan(now.minusDays(TTL_DAYS));
    }

    // ------------------------------------------------------------------ interne

    private RunnerDiagEventEntity toEntity(UUID userId, UUID hostId, JsonNode node) {
        String category = shorten(text(node, "cat"), MAX_CATEGORY_CHARS);
        String code = shorten(text(node, "code"), MAX_CODE_CHARS);
        if (category == null || code == null) {
            return null; // une ligne sans catégorie ni code n'apprend rien
        }
        RunnerDiagLevel level = RunnerDiagLevel.parse(text(node, "level"));
        return RunnerDiagEventEntity.builder()
                .userId(userId)
                .hostId(hostId)
                .level((level == null ? RunnerDiagLevel.DEFAULT : level).name())
                .category(category)
                .code(code)
                .message(shorten(text(node, "msg"), MAX_MESSAGE_CHARS))
                .fields(fieldsJson(node.path("fields")))
                .observedAt(parseInstant(text(node, "ts")))
                .build();
    }

    /** Sérialise la carte de champs en JSON compact borné ; {@code null} si vide ou illisible. */
    private String fieldsJson(JsonNode fields) {
        if (fields == null || !fields.isObject() || fields.isEmpty()) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(fields);
            return shorten(json, MAX_FIELDS_CHARS);
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    /** Taille l'anneau : supprime les lignes au-delà de la capacité du poste (les plus anciennes). */
    private void trimRing(UUID userId, UUID hostId) {
        long count = repository.countByUserIdAndHostId(userId, hostId);
        long excess = count - RING_CAPACITY;
        if (excess <= 0) {
            return;
        }
        List<UUID> oldest = repository.findOldestIds(userId, hostId,
                PageRequest.of(0, (int) Math.min(excess, Integer.MAX_VALUE)));
        if (!oldest.isEmpty()) {
            repository.deleteAllByIdInBatch(oldest);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private static OffsetDateTime parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso);
        } catch (DateTimeParseException primary) {
            try {
                return java.time.Instant.parse(iso).atOffset(java.time.ZoneOffset.UTC);
            } catch (DateTimeParseException ignored) {
                return null; // un horodatage illisible ne bloque pas l'ingestion
            }
        }
    }

    private static String shorten(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
