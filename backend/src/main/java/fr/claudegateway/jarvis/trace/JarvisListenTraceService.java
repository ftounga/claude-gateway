package fr.claudegateway.jarvis.trace;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Le journal de l'écoute</b> (F-233 / SF-233-15) : range sous le compte du jeton les événements de la boucle d'écoute
 * déclarés par l'écran Jarvis, nettoyés et bornés. Aucun contenu n'est accepté : noms, états, durées, noms d'erreurs.
 */
@Service
public class JarvisListenTraceService {

    /** Rétention : 7 jours. */
    public static final int RETENTION_DAYS = 7;
    /** Au plus autant d'événements par lot. */
    public static final int MAX_EVENTS_PER_BATCH = 100;
    /** Au-delà, sur 24 h, un compte n'écrit plus (un écran emballé ne remplit pas la base). */
    public static final long MAX_ROWS_PER_DAY = 20_000;
    static final int MAX_DETAIL = 120;
    static final long MAX_AT_MS = 1_000_000_000L;
    static final int MAX_REPEAT = 100_000;

    private static final Pattern EVENT = Pattern.compile("[a-z0-9_]{1,40}");
    private static final Pattern STATE = Pattern.compile("[a-z_]{0,16}");
    private static final Pattern PAGE_ID = Pattern.compile("[A-Za-z0-9-]{1,36}");
    private static final Pattern VERSION = Pattern.compile("[A-Za-z0-9._-]{1,24}");
    private static final Pattern DETAIL_DROP = Pattern.compile("[^A-Za-z0-9 _.:/>=+,-]");

    private final JarvisListenTraceRepository repository;
    private final Clock clock;

    public JarvisListenTraceService(JarvisListenTraceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Un lot de l'écran. F-255 / SF-255-01 : {@code now} = ms depuis l'ouverture de la page au moment de l'envoi
     * (facultatif) ; il date chaque événement à l'heure du serveur.
     */
    public record Batch(String pageId, String version, Boolean companion, List<Event> events, Long now) {

        public Batch(String pageId, String version, Boolean companion, List<Event> events) {
            this(pageId, version, companion, events, null);
        }
    }

    /**
     * Un événement : numéro, ms depuis l'ouverture, nom, état, mode, détail, répétitions. F-255 / SF-255-01 :
     * {@code tu} = le tour vocal courant (facultatif).
     */
    public record Event(Integer seq, Long t, String ev, String st, String md, String d, Integer n, String tu) {

        public Event(Integer seq, Long t, String ev, String st, String md, String d, Integer n) {
            this(seq, t, ev, st, md, d, n, null);
        }
    }

    /** Range le lot ; rend le nombre de lignes écrites (0 si vide, ignoré ou plafond atteint). */
    @Transactional
    public int record(UUID userId, Batch batch) {
        if (userId == null || batch == null || batch.events() == null || batch.events().isEmpty()) {
            return 0;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (repository.countByUserIdAndCreatedAtAfter(userId, now.minusDays(1)) >= MAX_ROWS_PER_DAY) {
            return 0;
        }
        String pageId = batch.pageId() != null && PAGE_ID.matcher(batch.pageId()).matches() ? batch.pageId() : "?";
        String version = batch.version() != null && VERSION.matcher(batch.version()).matches() ? batch.version() : null;
        boolean companion = Boolean.TRUE.equals(batch.companion());
        Long sentAt = batch.now() == null || batch.now() < 0 ? null : Math.min(MAX_AT_MS, batch.now());
        List<JarvisListenTraceEvent> rows = new ArrayList<>();
        for (Event e : batch.events().subList(0, Math.min(MAX_EVENTS_PER_BATCH, batch.events().size()))) {
            if (e == null || e.ev() == null || !EVENT.matcher(e.ev()).matches()) {
                continue;
            }
            long atMs = e.t() == null ? 0 : Math.min(MAX_AT_MS, Math.max(0, e.t()));
            rows.add(JarvisListenTraceEvent.builder()
                    .userId(userId)
                    .pageId(pageId)
                    .pageVersion(version)
                    .companion(companion)
                    .seq(e.seq() == null ? 0 : Math.max(0, e.seq()))
                    .atMs(atMs)
                    .turnId(JarvisTurnIds.clean(e.tu()))
                    .occurredAt(occurredAt(now, sentAt, atMs))
                    .event(e.ev())
                    .state(clean(e.st()))
                    .mode(clean(e.md()))
                    .detail(detail(e.d()))
                    .repeatCount(e.n() == null ? 1 : Math.min(MAX_REPEAT, Math.max(1, e.n())))
                    .createdAt(now)
                    .build());
        }
        repository.saveAll(rows);
        return rows.size();
    }

    /**
     * F-255 / SF-255-01 — l'heure serveur d'un événement : réception − (heure de page à l'envoi − heure de page de
     * l'événement) ; {@code null} sans heure d'envoi. Un événement daté après l'envoi est ramené à la réception.
     */
    static OffsetDateTime occurredAt(OffsetDateTime received, Long sentAtMs, long atMs) {
        if (sentAtMs == null) {
            return null;
        }
        return received.minus(Duration.ofMillis(Math.max(0, sentAtMs - atMs)));
    }

    /** F-255 / SF-255-01 — la requête d'un tour reçue par le cerveau rapide (événement serveur). */
    public static final String SERVER_CONVERSE = "srv_converse";

    /**
     * F-255 / SF-255-01 — un événement constaté par la <b>passerelle</b> (ex. la requête d'un tour reçue par le cerveau
     * rapide), daté à l'heure serveur exacte, sous le compte de l'appelant. Rien sans tour valide ou au-delà du plafond.
     * Ne lève jamais : le journal ne gêne pas le tour.
     */
    @Transactional
    public boolean recordServer(UUID userId, String pageId, String turnId, String event, String detail) {
        try {
            String turn = JarvisTurnIds.clean(turnId);
            if (userId == null || turn == null || event == null || !EVENT.matcher(event).matches()) {
                return false;
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            if (repository.countByUserIdAndCreatedAtAfter(userId, now.minusDays(1)) >= MAX_ROWS_PER_DAY) {
                return false;
            }
            String page = JarvisTurnIds.clean(pageId);
            repository.save(JarvisListenTraceEvent.builder()
                    .userId(userId)
                    .pageId(page == null ? "?" : page)
                    .seq(0)
                    .atMs(0)
                    .event(event)
                    .detail(detail(detail))
                    .createdAt(now)
                    .occurredAt(now)
                    .turnId(turn)
                    .build());
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Purge des événements de plus de {@link #RETENTION_DAYS} jours. */
    public int purge() {
        return repository.deleteOlderThan(OffsetDateTime.now(clock).minus(Duration.ofDays(RETENTION_DAYS)));
    }

    static String clean(String value) {
        if (value == null || value.isEmpty() || !STATE.matcher(value).matches()) {
            return null;
        }
        return value;
    }

    static String detail(String value) {
        if (value == null) {
            return null;
        }
        String kept = DETAIL_DROP.matcher(value).replaceAll("").trim();
        if (kept.isEmpty()) {
            return null;
        }
        return kept.length() > MAX_DETAIL ? kept.substring(0, MAX_DETAIL) : kept;
    }
}
