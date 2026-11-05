package fr.claudegateway.jarvis.state.sync;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.atelier.veille.JarvisWatchPassage;
import fr.claudegateway.atelier.veille.JarvisWatchRun;
import fr.claudegateway.jarvis.state.JarvisStateProperties;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.jarvis.state.JarvisStateSettings;
import fr.claudegateway.jarvis.state.JarvisStateVocabulary;
import fr.claudegateway.jarvis.state.capture.CaptureReadEnqueuer;
import fr.claudegateway.jarvis.state.capture.CaptureReadJobHandler;
import fr.claudegateway.jarvis.state.extract.HistoryNotesReader;
import fr.claudegateway.jarvis.state.extract.HistoryNotesSelector;
import fr.claudegateway.jarvis.state.extract.StateExtractJobHandler;
import fr.claudegateway.jarvis.state.releve.JarvisReleveService;
import fr.claudegateway.jarvis.state.releve.JarvisReleveTriggers;
import fr.claudegateway.runner.RunnerLiveness;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerTargets;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.runner.host.RunnerHostService;
import fr.claudegateway.shared.jobs.AsyncJobHandler;
import fr.claudegateway.shared.jobs.AsyncJobQueue;

/**
 * <b>« Synchroniser », l'exécutant</b> (F-247 / SF-247-16) : le type {@code STATE_SYNC} de la file durable. Un travail =
 * <b>une tranche</b> (≤ 25 réponses, ≤ 2 captures ou 1 sonde, ≤ 5 s) ; la tranche persiste son curseur et sa
 * progression puis remet la suivante en file, <b>dans la même transaction</b> (étape gardée : un travail rejoué par le
 * faucheur est périmé et abandonné, la chaîne ne se dédouble jamais). Trois phases :
 * <ol>
 * <li><b>Historique</b> (sans IA, 0 €) : les sorties d'outils gardées ({@code terminal_json}) des sujets du client
 * repassent dans les extracteurs, des plus anciennes aux plus récentes ; chaque fait garde l'heure de sa sortie
 * d'origine, l'upsert n'écrase jamais un fait plus récent, le filtre des secrets s'applique ;</li>
 * <li><b>Constats sur l'historique</b> (SF-247-17, phase numérotée 4, jouée juste après l'historique, sans le poste) :
 * les sorties utiles non couvertes sont lues par le modèle rapide, un appel par tranche, garde de citation inchangée,
 * dans l'enveloppe ponctuelle du passage ;</li>
 * <li><b>Captures déjà déposées</b> (poste allumé) : relues sur le poste puis confiées à la lecture SF-247-12
 * (priorité basse, mêmes plafonds, même coût compté) ;</li>
 * <li><b>Relevé du jour</b> (poste allumé, à jour, relevé mis en place) : chaque sonde passe une fois.</li>
 * </ol>
 * <b>Céder la place (Z4)</b> : tour vivant sur un sujet du client ⇒ la tranche ne travaille pas et revient dans 60 s ;
 * phases 2/3 : en plus, aucune commande hors relevés depuis 2 min. Rien n'est jamais fait dans le fil d'un tour.
 */
@Component
public class StateSyncJobHandler implements AsyncJobHandler {

    private static final Logger log = LoggerFactory.getLogger(StateSyncJobHandler.class);
    public static final String KIND = "STATE_SYNC";
    /** Plus basse que les travaux du fil de l'eau (100). */
    public static final int PRIORITY = 200;
    static final int HISTORY_BATCH = 25;
    static final int CAPTURE_BATCH = 2;
    static final int MAX_CAPTURES_IN_FLIGHT = 10;
    static final long SLICE_BUDGET_MS = 5_000;
    static final Duration RETRY_TURN = Duration.ofSeconds(60);
    static final Duration RETRY_HOST = Duration.ofMinutes(2);
    static final Duration RETRY_READING = Duration.ofSeconds(15);
    static final Duration MAX_WAIT = Duration.ofDays(7);
    static final int MAX_CAPTURE_BYTES = 5 * 1024 * 1024;
    static final int CHUNK_BYTES = 360 * 1024;
    static final String AUDIT_TOOL = "releve_sonde:capture";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JarvisStateSyncStore store;
    private final AsyncJobQueue queue;
    private final JarvisStateRegistry registry;
    private final JarvisStateProperties properties;
    private final WorkspaceRepository workspaces;
    private final AtelierMessageRepository messages;
    private final StateExtractJobHandler extractor;
    private final CaptureReadEnqueuer captures;
    private final RunnerToolGateway runner;
    private final RunnerLiveness liveness;
    private final RunnerHostService hosts;
    private final LiveTurnRegistry liveTurns;
    private final JarvisReleveService releve;
    private final JarvisReleveTriggers releveTriggers;
    private final JarvisWatchPassage passage;
    private final RunnerAuditService audit;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final boolean enabled;
    /** SF-247-17 — les constats sur l'historique (phase 4). */
    private HistoryNotesReader notes;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setNotes(HistoryNotesReader notes) {
        this.notes = notes;
    }

    public StateSyncJobHandler(JarvisStateSyncStore store, AsyncJobQueue queue, JarvisStateRegistry registry,
            JarvisStateProperties properties, WorkspaceRepository workspaces, AtelierMessageRepository messages,
            StateExtractJobHandler extractor, CaptureReadEnqueuer captures, RunnerToolGateway runner,
            RunnerLiveness liveness, RunnerHostService hosts, LiveTurnRegistry liveTurns, JarvisReleveService releve,
            JarvisReleveTriggers releveTriggers, JarvisWatchPassage passage, RunnerAuditService audit, JdbcTemplate jdbc,
            PlatformTransactionManager transactionManager, Clock clock,
            @Value("${app.jarvis.state.sync-enabled:true}") boolean enabled) {
        this.store = store;
        this.queue = queue;
        this.registry = registry;
        this.properties = properties;
        this.workspaces = workspaces;
        this.messages = messages;
        this.extractor = extractor;
        this.captures = captures;
        this.runner = runner;
        this.liveness = liveness;
        this.hosts = hosts;
        this.liveTurns = liveTurns;
        this.releve = releve;
        this.releveTriggers = releveTriggers;
        this.passage = passage;
        this.audit = audit;
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(transactionManager);
        this.clock = clock;
        this.enabled = enabled;
    }

    @Override
    public String kind() {
        return KIND;
    }

    /** Coupe-circuit {@code APP_JARVIS_STATE_SYNC_ENABLED} : coupé, les tranches attendent en file. */
    @Override
    public boolean active() {
        return enabled && properties.enabled();
    }

    public boolean enabled() {
        return active();
    }

    /** La charge utile d'une tranche : le passage et son étape. */
    public static String payload(UUID syncId, int step) {
        return "{\"syncId\":\"" + syncId + "\",\"step\":" + step + "}";
    }

    /** La marque des lectures de captures remises par un passage (progression, dépense, annulation). */
    public static String marker(UUID syncId) {
        return "\"syncId\":\"" + syncId + "\"";
    }

    /** Met une tranche en file (dans la transaction de l'appelant s'il y en a une). */
    void schedule(UUID userId, UUID syncId, int step, Duration delay) {
        queue.enqueue(new AsyncJobQueue.Request(userId, KIND, PRIORITY, payload(syncId, step), null, 3),
                delay == null ? null : clock.instant().plus(delay));
    }

    /** Ce qu'une tranche décide pour la suite : finie, ou la prochaine tranche dans {@code delay}. */
    private record Next(boolean finished, Duration delay) {

        static Next now() {
            return new Next(false, null);
        }

        static Next in(Duration d) {
            return new Next(false, d);
        }

        static Next end() {
            return new Next(true, null);
        }
    }

    @Override
    public Outcome handle(AsyncJobQueue.Job job) {
        UUID userId = job.userId();
        UUID syncId;
        int step;
        try {
            JsonNode p = MAPPER.readTree(job.payload());
            syncId = UUID.fromString(p.path("syncId").asText());
            step = p.path("step").asInt(-1);
        } catch (Exception e) {
            return Outcome.drop("payload");
        }
        Optional<SyncRow> found = store.find(userId, syncId);
        if (found.isEmpty() || !found.get().active()) {
            return Outcome.drop("finished");
        }
        SyncRow s = found.get();
        if (s.getStep() != step) {
            return Outcome.drop("stale");
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        JarvisStateSettings settings = registry.settingsOf(userId, s.getHostId());
        Next next;
        if (!properties.enabled() || !settings.isEnabled()) {
            next = finishAll(s, SyncRow.P_SKIPPED, "registry_off");
        } else {
            next = switch (s.getPhase()) {
                case 1 -> history(s, settings, now);
                case SyncRow.PHASE_NOTES -> notesPhase(s, settings, now);
                case 2 -> capturesPhase(s, settings, now);
                default -> relevePhase(s, settings, now);
            };
        }
        OffsetDateTime end = OffsetDateTime.now(clock);
        s.setUpdatedAt(end);
        if (next.finished()) {
            s.setStatus(SyncRow.DONE);
            s.setNote(null);
            s.setFinishedAt(end);
        }
        Boolean saved = tx.execute(t -> {
            boolean ok = store.advance(s, step);
            if (ok && !next.finished()) {
                schedule(userId, syncId, step + 1, next.delay());
            }
            return ok;
        });
        if (!Boolean.TRUE.equals(saved)) {
            // Annulé pendant la tranche : rien ne doit survivre de ce qu'elle a remis.
            queue.dropPending(userId, CaptureReadJobHandler.KIND, marker(syncId), "sync_cancelled");
            return Outcome.drop("cancelled");
        }
        if (next.finished()) {
            log.info("Synchroniser : client {} terminé — historique {} faits ({} ms), constats {} sorties / {} appels / "
                    + "{} faits écrits / {} retenus / {} rejetés par la garde ({} ms, {} USD, enveloppe {} EUR, {}), "
                    + "captures {} lues / {} lignes ({} ms, {} USD), relevé {} faits ({} ms)", s.getHostId(),
                    s.getP1Facts(), s.getP1Ms(), s.getP4Done(), s.getP4Calls(), s.getP4Facts(), s.getP4Kept(),
                    s.getP4Rejected(), s.getP4Ms(), s.getP4CostUsd(), s.getP4EnvelopeEur(), s.getP4Detail(),
                    s.getP2Read(), s.getP2Facts(), s.getP2Ms(), s.getP2CostUsd(), s.getP3Facts(), s.getP3Ms());
        }
        return Outcome.done();
    }

    // ------------------------------------------------------------------ phase 1 : l'historique

    private Next history(SyncRow s, JarvisStateSettings settings, OffsetDateTime now) {
        if (!properties.extract() || !settings.isExtractFromTurns()) {
            s.setP1Status(SyncRow.P_SKIPPED);
            s.setP1Detail("source_off");
            return toPhase(s, SyncRow.PHASE_NOTES);
        }
        int window = Math.max(1, Math.min(s.getWindowDays(), settings.getFactRetentionDays()));
        OffsetDateTime since = s.getStartedAt().minusDays(window);
        if (SyncRow.P_PENDING.equals(s.getP1Status())) {
            Integer total = jdbc.queryForObject("SELECT count(*) FROM atelier_messages m WHERE m.user_id = ? AND "
                    + "m.workspace_id IN (SELECT w.id FROM workspaces w WHERE w.user_id = ? AND w.host_id = ?) AND "
                    + "m.terminal_json IS NOT NULL AND m.created_at >= ?", Integer.class, s.getUserId(),
                    s.getUserId(), s.getHostId(), Timestamp.from(since.toInstant()));
            s.setP1Total(total == null ? 0 : total);
            s.setP1Status(SyncRow.P_RUNNING);
            s.setP1Detail("window:" + window);
            s.setCursorAt(null);
            s.setCursorId(null);
        }
        Map<UUID, Workspace> subjects = subjects(s);
        if (turnLive(s.getUserId(), subjects.keySet())) {
            return pause(s, 1);
        }
        s.setNote(null);
        s.setP1Status(SyncRow.P_RUNNING);
        long t0 = System.nanoTime();
        List<Object[]> rows = nextMessages(s, since);
        int processed = 0;
        boolean estimate = notesWillRun(s, settings);
        List<HistoryNotesSelector.Candidate> toEstimate = new ArrayList<>();
        for (Object[] r : rows) {
            UUID messageId = (UUID) r[0];
            Workspace w = subjects.get((UUID) r[1]);
            Optional<AtelierMessage> m = messages.findByIdAndUserId(messageId, s.getUserId());
            if (w != null && m.isPresent()) {
                try {
                    StateExtractJobHandler.Summary sum = extractor.extract(s.getUserId(), s.getHostId(), w.getId(),
                            w.getName(), m.get(), false);
                    s.setP1Facts(s.getP1Facts() + sum.facts());
                    if (estimate) {
                        // SF-247-17 — l'estimation des constats sur l'historique, sans IA, pendant le même parcours.
                        List<HistoryNotesSelector.Candidate> c = extractor.historyCandidates(s.getUserId(), w.getId(),
                                m.get());
                        toEstimate.addAll(c);
                        s.setP4EstCalls(s.getP4EstCalls() + HistoryNotesSelector.group(c,
                                HistoryNotesSelector.MAX_GROUP_CHARS).size());
                    }
                } catch (RuntimeException e) {
                    log.debug("Synchroniser : réponse ignorée ({})", e.getClass().getSimpleName());
                }
            }
            s.setP1Done(Math.min(s.getP1Total(), s.getP1Done() + 1));
            s.setCursorAt((OffsetDateTime) r[2]);
            s.setCursorId(messageId);
            processed++;
            if ((System.nanoTime() - t0) / 1_000_000 > SLICE_BUDGET_MS) {
                break;
            }
        }
        if (!toEstimate.isEmpty()) {
            // Ce qui a déjà été constaté (passage précédent, constats au fil de l'eau) ne compte pas.
            java.util.Set<String> seen = notes.seen(s.getUserId(), s.getHostId(),
                    toEstimate.stream().map(HistoryNotesSelector.Candidate::fingerprint).toList());
            for (HistoryNotesSelector.Candidate c : toEstimate) {
                if (!seen.contains(c.fingerprint())) {
                    s.setP4Total(s.getP4Total() + 1);
                    s.setP4EstChars(s.getP4EstChars() + c.body().length() + c.command().length());
                }
            }
        }
        s.setP1Ms(s.getP1Ms() + (System.nanoTime() - t0) / 1_000_000);
        if (processed == rows.size() && rows.size() < HISTORY_BATCH) {
            s.setP1Status(SyncRow.P_DONE);
            s.setP1Done(s.getP1Total());
            return toPhase(s, SyncRow.PHASE_NOTES);
        }
        return Next.now();
    }

    /** Les réponses suivantes du client (curseur {@code created_at, id}), les plus anciennes d'abord. */
    private List<Object[]> nextMessages(SyncRow s, OffsetDateTime since) {
        String base = "SELECT m.id, m.workspace_id, m.created_at FROM atelier_messages m WHERE m.user_id = ? AND "
                + "m.workspace_id IN (SELECT w.id FROM workspaces w WHERE w.user_id = ? AND w.host_id = ?) AND "
                + "m.terminal_json IS NOT NULL AND m.created_at >= ?";
        org.springframework.jdbc.core.RowMapper<Object[]> map = (rs, i) -> new Object[] {
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getTimestamp(3).toInstant().atOffset(ZoneOffset.UTC)};
        if (s.getCursorAt() == null) {
            return jdbc.query(base + " ORDER BY m.created_at, m.id LIMIT ?", map, s.getUserId(), s.getUserId(),
                    s.getHostId(), Timestamp.from(since.toInstant()), HISTORY_BATCH);
        }
        Timestamp c = Timestamp.from(s.getCursorAt().toInstant());
        return jdbc.query(base + " AND (m.created_at > ? OR (m.created_at = ? AND m.id > ?)) ORDER BY m.created_at, m.id "
                + "LIMIT ?", map, s.getUserId(), s.getUserId(), s.getHostId(), Timestamp.from(since.toInstant()), c, c,
                s.getCursorId(), HISTORY_BATCH);
    }

    // ------------------------------------------------------------------ phase 4 : les constats sur l'historique

    /** Au-delà de ce nombre d'échecs du fournisseur, la phase s'arrête (relancer reprend où elle s'est arrêtée). */
    static final int MAX_NOTES_ERRORS = 5;

    /** Les constats sur l'historique tourneront-ils pour ce passage ? (sources, coupe-circuits, enveloppe). */
    private boolean notesWillRun(SyncRow s, JarvisStateSettings settings) {
        return notes != null && properties.notes() && settings.isNotesEnabled() && properties.extract()
                && settings.isExtractFromTurns() && s.getP4EnvelopeEur() != null && s.getP4EnvelopeEur().signum() > 0
                && SyncRow.P_PENDING.equals(s.getP4Status());
    }

    /**
     * SF-247-17 — <b>les constats sur l'historique</b> (sans le poste) : les sorties utiles des réponses passées
     * ({@link HistoryNotesSelector}), non encore constatées, sont lues <b>un appel par tranche</b> (sorties d'un même
     * tour regroupées), des plus anciennes aux plus récentes, dans l'enveloppe ponctuelle du passage. Cède aux tours
     * comme l'historique. Une sortie lue est marquée : jamais relue.
     */
    private Next notesPhase(SyncRow s, JarvisStateSettings settings, OffsetDateTime now) {
        if (SyncRow.P_PENDING.equals(s.getP4Status())) {
            if (notes == null || !properties.notes() || !settings.isNotesEnabled() || !properties.extract()
                    || !settings.isExtractFromTurns()) {
                s.setP4Status(SyncRow.P_SKIPPED);
                s.setP4Detail("source_off");
                return toPhase(s, 2);
            }
            if (s.getP4EnvelopeEur() == null || s.getP4EnvelopeEur().signum() <= 0) {
                s.setP4Status(SyncRow.P_SKIPPED);
                s.setP4Detail("envelope_zero");
                return toPhase(s, 2);
            }
            if (s.getP4Total() == 0) {
                s.setP4Status(SyncRow.P_DONE);
                s.setP4Detail("nothing");
                return toPhase(s, 2);
            }
            s.setP4Status(SyncRow.P_RUNNING);
            s.setCursorAt(null);
            s.setCursorId(null);
        }
        Map<UUID, Workspace> subjects = subjects(s);
        if (turnLive(s.getUserId(), subjects.keySet())) {
            return pause(s, SyncRow.PHASE_NOTES);
        }
        if (notes.jarvisHardStop(s.getUserId())) {
            return endNotes(s, "budget_hard");
        }
        s.setNote(null);
        s.setP4Status(SyncRow.P_RUNNING);
        int window = Math.max(1, Math.min(s.getWindowDays(), settings.getFactRetentionDays()));
        OffsetDateTime since = s.getStartedAt().minusDays(window);
        long t0 = System.nanoTime();
        try {
            List<Object[]> rows = nextMessages(s, since);
            for (Object[] r : rows) {
                UUID messageId = (UUID) r[0];
                Workspace w = subjects.get((UUID) r[1]);
                Optional<AtelierMessage> m = messages.findByIdAndUserId(messageId, s.getUserId());
                List<HistoryNotesSelector.Candidate> unseen = List.of();
                if (w != null && m.isPresent()) {
                    List<HistoryNotesSelector.Candidate> all = extractor.historyCandidates(s.getUserId(), w.getId(),
                            m.get());
                    java.util.Set<String> seen = notes.seen(s.getUserId(), s.getHostId(),
                            all.stream().map(HistoryNotesSelector.Candidate::fingerprint).toList());
                    unseen = all.stream().filter(c -> !seen.contains(c.fingerprint())).toList();
                }
                if (!unseen.isEmpty()) {
                    List<HistoryNotesSelector.Candidate> group = HistoryNotesSelector.group(unseen,
                            HistoryNotesSelector.MAX_GROUP_CHARS).get(0);
                    java.math.BigDecimal spent = notes.toEur(s.getP4CostUsd());
                    java.math.BigDecimal worst = notes.toEur(notes.worstUsd(HistoryNotesReader.chars(group)));
                    if (spent.add(worst).compareTo(s.getP4EnvelopeEur()) > 0) {
                        // L'enveloppe ne sera jamais dépassée : on s'arrête avant l'appel qui pourrait la crever.
                        return endNotes(s, "envelope");
                    }
                    HistoryNotesReader.Outcome out;
                    try {
                        out = notes.read(s.getUserId(), s.getHostId(), w.getId(), messageId, group);
                    } catch (RuntimeException e) {
                        s.setP4Errors(s.getP4Errors() + 1);
                        log.warn("Synchroniser : constat sur l'historique en échec ({})", e.getClass().getSimpleName());
                        return s.getP4Errors() >= MAX_NOTES_ERRORS ? endNotes(s, "provider_error")
                                : Next.in(RETRY_TURN);
                    }
                    s.setP4Calls(s.getP4Calls() + 1);
                    s.setP4Done(s.getP4Done() + group.size());
                    s.setP4Facts(s.getP4Facts() + out.written());
                    s.setP4Kept(s.getP4Kept() + out.kept());
                    s.setP4Rejected(s.getP4Rejected() + out.rejected());
                    s.setP4CostUsd(s.getP4CostUsd().add(out.usd()));
                    if (unseen.size() > group.size()) {
                        return Next.now(); // la suite de ce tour à la tranche suivante (même curseur)
                    }
                    s.setCursorAt((OffsetDateTime) r[2]);
                    s.setCursorId(messageId);
                    return Next.now(); // un appel par tranche
                }
                s.setCursorAt((OffsetDateTime) r[2]);
                s.setCursorId(messageId);
                if ((System.nanoTime() - t0) / 1_000_000 > SLICE_BUDGET_MS) {
                    return Next.now();
                }
            }
            if (rows.size() < HISTORY_BATCH) {
                return endNotes(s, null);
            }
            return Next.now();
        } finally {
            s.setP4Ms(s.getP4Ms() + (System.nanoTime() - t0) / 1_000_000);
        }
    }

    /** Fin de la phase des constats (toutes lues, enveloppe atteinte, budget à l'arrêt dur, pannes) ; place aux captures. */
    private Next endNotes(SyncRow s, String detail) {
        s.setP4Status(SyncRow.P_DONE);
        s.setP4Detail(detail);
        s.setP4Total(Math.max(s.getP4Total(), s.getP4Done()));
        return toPhase(s, 2);
    }

    // ------------------------------------------------------------------ phase 2 : les captures déjà déposées

    private static final String DEPOSITS = "FROM atelier_deposited_files d WHERE d.user_id = ? AND d.workspace_id IN "
            + "(SELECT w.id FROM workspaces w WHERE w.user_id = ? AND w.host_id = ?) AND d.created_at >= ? AND "
            + "d.size_bytes > 0 AND d.size_bytes <= " + MAX_CAPTURE_BYTES + " AND (lower(d.path) LIKE '%.png' OR "
            + "lower(d.path) LIKE '%.jpg' OR lower(d.path) LIKE '%.jpeg' OR lower(d.path) LIKE '%.webp') AND NOT EXISTS "
            + "(SELECT 1 FROM jarvis_conversation_captures c WHERE c.user_id = d.user_id AND c.deposit_id = d.id)";

    private Next capturesPhase(SyncRow s, JarvisStateSettings settings, OffsetDateTime now) {
        if (!properties.captures() || !settings.isCapturesEnabled()) {
            s.setP2Status(SyncRow.P_SKIPPED);
            s.setP2Detail("source_off");
            return toPhase(s, 3);
        }
        int window = Math.max(1, Math.min(s.getWindowDays(), settings.getCaptureRetentionDays()));
        OffsetDateTime since = s.getStartedAt().minusDays(window);
        if (SyncRow.P_PENDING.equals(s.getP2Status())) {
            Integer total = jdbc.queryForObject("SELECT count(*) " + DEPOSITS, Integer.class, s.getUserId(),
                    s.getUserId(), s.getHostId(), Timestamp.from(since.toInstant()));
            s.setP2Total(total == null ? 0 : total);
            s.setP2StartedAt(now);
            s.setP2Status(SyncRow.P_RUNNING);
            s.setP2Detail("window:" + window);
            s.setCursorAt(null);
            s.setCursorId(null);
        }
        AsyncJobQueue.MarkerStats stats = measureCaptures(s);
        boolean sentAll = s.getP2Done() >= s.getP2Total();
        if (sentAll && stats.active() == 0) {
            s.setNote(null);
            s.setP2Status(SyncRow.P_DONE);
            return toPhase(s, 3);
        }
        if (sentAll || stats.active() >= MAX_CAPTURES_IN_FLIGHT) {
            s.setNote(SyncRow.NOTE_READING);
            s.setP2Status(SyncRow.P_RUNNING);
            return Next.in(RETRY_READING);
        }
        Next wait = waitForHost(s, 2, now);
        if (wait != null) {
            return wait;
        }
        if (!releve.quiet(s.getUserId(), s.getHostId(), now)) {
            return pause(s, 2);
        }
        s.setNote(null);
        s.setP2Status(SyncRow.P_RUNNING);
        long t0 = System.nanoTime();
        try {
            List<Object[]> rows = nextDeposits(s, since);
            if (rows.isEmpty()) {
                s.setP2Done(s.getP2Total());
                return Next.now();
            }
            Map<UUID, Workspace> subjects = subjects(s);
            for (Object[] r : rows) {
                UUID depositId = (UUID) r[0];
                Workspace w = subjects.get((UUID) r[1]);
                String path = (String) r[2];
                OffsetDateTime depositedAt = (OffsetDateTime) r[3];
                if (w != null && queue.markerStats(s.getUserId(), CaptureReadJobHandler.KIND,
                        "\"depositId\":\"" + depositId + "\"").active() == 0) {
                    Read read = readOnHost(s.getUserId(), w, path);
                    if (read.offline()) {
                        return waitForHost(s, 2, now, true);
                    }
                    if (read.unsupported()) {
                        s.setP2Status(SyncRow.P_SKIPPED);
                        s.setP2Detail("runner_old");
                        return toPhase(s, 3);
                    }
                    if (read.bytes() == null) {
                        s.setP2Missing(s.getP2Missing() + 1);
                    } else if (!captures.enqueueExisting(w, depositId, read.bytes(), depositedAt.toInstant(),
                            PRIORITY, s.getId())) {
                        s.setP2Dropped(s.getP2Dropped() + 1);
                    }
                }
                s.setP2Done(Math.min(s.getP2Total(), s.getP2Done() + 1));
                s.setCursorAt(depositedAt);
                s.setCursorId(depositId);
            }
            if (rows.size() < CAPTURE_BATCH) {
                s.setP2Done(s.getP2Total());
            }
            return Next.now();
        } finally {
            s.setP2Ms(s.getP2Ms() + (System.nanoTime() - t0) / 1_000_000);
        }
    }

    /** Les lectures de ce passage : lues, abandonnées (plafond), coût ; les lignes transcrites du client depuis. */
    private AsyncJobQueue.MarkerStats measureCaptures(SyncRow s) {
        AsyncJobQueue.MarkerStats stats = queue.markerStats(s.getUserId(), CaptureReadJobHandler.KIND,
                marker(s.getId()));
        s.setP2Read((int) stats.count(AsyncJobQueue.DONE));
        s.setP2CostUsd(stats.costUsd());
        int dropped = (int) (stats.count(AsyncJobQueue.DROPPED) + stats.count(AsyncJobQueue.FAILED));
        s.setP2Detail(dropped > 0 ? "capped:" + dropped : s.getP2Detail());
        if (s.getP2StartedAt() != null) {
            Integer lines = jdbc.queryForObject("SELECT COALESCE(sum(line_count), 0) FROM jarvis_conversation_captures "
                    + "WHERE user_id = ? AND host_id = ? AND created_at >= ?", Integer.class, s.getUserId(),
                    s.getHostId(), Timestamp.from(s.getP2StartedAt().toInstant()));
            s.setP2Facts(lines == null ? 0 : lines);
        }
        return stats;
    }

    private List<Object[]> nextDeposits(SyncRow s, OffsetDateTime since) {
        String select = "SELECT d.id, d.workspace_id, d.path, d.created_at " + DEPOSITS;
        org.springframework.jdbc.core.RowMapper<Object[]> map = (rs, i) -> new Object[] {
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                rs.getTimestamp(4).toInstant().atOffset(ZoneOffset.UTC)};
        if (s.getCursorAt() == null) {
            return jdbc.query(select + " ORDER BY d.created_at, d.id LIMIT ?", map, s.getUserId(), s.getUserId(),
                    s.getHostId(), Timestamp.from(since.toInstant()), CAPTURE_BATCH);
        }
        Timestamp c = Timestamp.from(s.getCursorAt().toInstant());
        return jdbc.query(select + " AND (d.created_at > ? OR (d.created_at = ? AND d.id > ?)) ORDER BY d.created_at, "
                + "d.id LIMIT ?", map, s.getUserId(), s.getUserId(), s.getHostId(), Timestamp.from(since.toInstant()),
                c, c, s.getCursorId(), CAPTURE_BATCH);
    }

    /** Ce que le poste a rendu d'un fichier déposé. */
    record Read(byte[] bytes, boolean offline, boolean unsupported) {
    }

    /** Relit un dépôt sur le poste, par tranches ({@code read_file_bytes}), journal {@value #AUDIT_TOOL}. */
    Read readOnHost(UUID userId, Workspace w, String path) {
        RunnerTarget target = RunnerTargets.of(w);
        String callId = "sync-" + UUID.randomUUID();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        RunnerCallResult last = null;
        for (int chunk = 0; chunk < MAX_CAPTURE_BYTES / CHUNK_BYTES + 2; chunk++) {
            RunnerCallResult r;
            try {
                r = runner.readFileBytes(target, callId + "." + chunk, path, out.size(), CHUNK_BYTES);
            } catch (RuntimeException e) {
                return new Read(null, false, false);
            }
            last = r;
            if (r == null || !r.ok()) {
                record(userId, target, callId, path, r);
                String code = r == null ? null : r.errorCode();
                return new Read(null, RunnerErrorCodes.RUNNER_UNAVAILABLE.equals(code),
                        chunk == 0 && RunnerErrorCodes.UNSUPPORTED_TOOL.equals(code));
            }
            byte[] part;
            try {
                part = Base64.getDecoder().decode(r.content() == null ? "" : r.content());
            } catch (IllegalArgumentException e) {
                record(userId, target, callId, path, r);
                return new Read(null, false, false);
            }
            out.write(part, 0, part.length);
            if (out.size() > MAX_CAPTURE_BYTES) {
                record(userId, target, callId, path, r);
                return new Read(null, false, false);
            }
            if (!r.truncated() || part.length == 0) {
                record(userId, target, callId, path, last);
                return new Read(out.toByteArray(), false, false);
            }
        }
        record(userId, target, callId, path, last);
        return new Read(null, false, false);
    }

    private void record(UUID userId, RunnerTarget target, String callId, String path, RunnerCallResult r) {
        if (audit == null || r == null) {
            return;
        }
        try {
            audit.recordCall(userId, target, callId, AUDIT_TOOL, path, r);
        } catch (RuntimeException e) {
            log.debug("Synchroniser : lecture non journalisée ({})", e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ phase 3 : le relevé du jour

    private Next relevePhase(SyncRow s, JarvisStateSettings settings, OffsetDateTime now) {
        if (!properties.releve() || !settings.isReleveEnabled()) {
            s.setP3Status(SyncRow.P_SKIPPED);
            s.setP3Detail("source_off");
            return Next.end();
        }
        List<JarvisReleveTriggers.Probe> probes = new ArrayList<>(releveTriggers.probesOf(s.getUserId(),
                s.getHostId()));
        probes.sort(Comparator.comparing(p -> p.watch().getId().toString()));
        if (SyncRow.P_PENDING.equals(s.getP3Status())) {
            if (probes.isEmpty()) {
                s.setP3Status(SyncRow.P_SKIPPED);
                s.setP3Detail("not_setup");
                return Next.end();
            }
            s.setP3Total(probes.size());
            s.setP3StartedAt(now);
            s.setP3Status(SyncRow.P_RUNNING);
        }
        Next wait = waitForHost(s, 3, now);
        if (wait != null) {
            return wait;
        }
        if (!hosts.declaredCapabilities(s.getHostId()).contains(JarvisReleveService.LOW_PRIORITY)) {
            s.setP3Status(SyncRow.P_SKIPPED);
            s.setP3Detail("runner_old");
            return Next.end();
        }
        if (releve.disarmed(s.getUserId(), s.getHostId(), now)) {
            s.setP3Status(SyncRow.P_SKIPPED);
            s.setP3Detail("disarmed");
            return Next.end();
        }
        if (!releve.quiet(s.getUserId(), s.getHostId(), now)) {
            return pause(s, 3);
        }
        s.setNote(null);
        s.setP3Status(SyncRow.P_RUNNING);
        if (s.getP3Done() >= probes.size()) {
            return endReleve(s);
        }
        long t0 = System.nanoTime();
        JarvisReleveTriggers.Probe probe = probes.get(s.getP3Done());
        JarvisWatchRun run = passage.run(probe.watch(), probe.workspace(), null, JarvisWatchRun.MANUAL);
        s.setP3Ms(s.getP3Ms() + (System.nanoTime() - t0) / 1_000_000);
        if (run != null && JarvisWatchRun.YIELDED.equals(run.getStatus())) {
            return pause(s, 3); // le poste travaillait : on reprend la même sonde plus tard
        }
        s.setP3Done(s.getP3Done() + 1);
        s.setP3Facts(store.factsSince(s.getUserId(), s.getHostId(), JarvisStateVocabulary.SOURCE_RELEVE,
                s.getP3StartedAt()));
        return s.getP3Done() >= probes.size() ? endReleve(s) : Next.now();
    }

    private Next endReleve(SyncRow s) {
        s.setP3Status(SyncRow.P_DONE);
        s.setP3Facts(store.factsSince(s.getUserId(), s.getHostId(), JarvisStateVocabulary.SOURCE_RELEVE,
                s.getP3StartedAt()));
        return Next.end();
    }

    // ------------------------------------------------------------------ communs

    /** Poste éteint : la phase attend (nouvel essai dans 2 min) ; au-delà de 7 j, elle expire. {@code null} = présent. */
    private Next waitForHost(SyncRow s, int phase, OffsetDateTime now) {
        return liveness.isAlive(s.getUserId(), s.getHostId()) ? clearWait(s) : waitForHost(s, phase, now, true);
    }

    private Next clearWait(SyncRow s) {
        s.setWaitingSince(null);
        return null;
    }

    private Next waitForHost(SyncRow s, int phase, OffsetDateTime now, boolean offline) {
        if (s.getWaitingSince() == null) {
            s.setWaitingSince(now);
        }
        if (s.getWaitingSince().isBefore(now.minus(MAX_WAIT))) {
            s.setWaitingSince(null);
            s.setNote(null);
            setPhaseStatus(s, phase, SyncRow.P_EXPIRED);
            return phase == 2 ? toPhase(s, 3) : Next.end();
        }
        s.setNote(SyncRow.NOTE_HOST_OFF);
        setPhaseStatus(s, phase, SyncRow.P_WAITING);
        return Next.in(RETRY_HOST);
    }

    /** Un tour est en cours : la phase se met en pause et revient dans 60 s. */
    private Next pause(SyncRow s, int phase) {
        s.setNote(SyncRow.NOTE_TURN);
        setPhaseStatus(s, phase, SyncRow.P_PAUSED);
        return Next.in(RETRY_TURN);
    }

    private static void setPhaseStatus(SyncRow s, int phase, String status) {
        switch (phase) {
            case 1 -> s.setP1Status(status);
            case SyncRow.PHASE_NOTES -> s.setP4Status(status);
            case 2 -> s.setP2Status(status);
            default -> s.setP3Status(status);
        }
    }

    private static Next toPhase(SyncRow s, int phase) {
        s.setPhase(phase);
        s.setNote(null);
        s.setCursorAt(null);
        s.setCursorId(null);
        s.setWaitingSince(null);
        return Next.now();
    }

    /** Registre coupé en route : tout ce qui reste est sauté, le passage se termine. */
    private static Next finishAll(SyncRow s, String status, String detail) {
        for (int phase = 1; phase <= SyncRow.PHASE_NOTES; phase++) {
            String current = phase == 1 ? s.getP1Status() : phase == 2 ? s.getP2Status()
                    : phase == 3 ? s.getP3Status() : s.getP4Status();
            if (!SyncRow.P_DONE.equals(current) && !SyncRow.P_SKIPPED.equals(current)
                    && !SyncRow.P_EXPIRED.equals(current)) {
                setPhaseStatus(s, phase, status);
                switch (phase) {
                    case 1 -> s.setP1Detail(detail);
                    case 2 -> s.setP2Detail(detail);
                    case 3 -> s.setP3Detail(detail);
                    default -> s.setP4Detail(detail);
                }
            }
        }
        return Next.end();
    }

    /** Les sujets du client, sous le compte. */
    private Map<UUID, Workspace> subjects(SyncRow s) {
        Map<UUID, Workspace> out = new HashMap<>();
        for (Workspace w : workspaces.findByUserIdAndHostId(s.getUserId(), s.getHostId())) {
            if (s.getUserId().equals(w.getUserId())) {
                out.put(w.getId(), w);
            }
        }
        return out;
    }

    private boolean turnLive(UUID userId, Set<UUID> subjects) {
        return liveTurns.liveTurnsOf(userId).stream().anyMatch(t -> subjects.contains(t.workspaceId()));
    }
}
