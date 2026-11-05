package fr.claudegateway.jarvis.state;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import fr.claudegateway.runner.RunnerStatusService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostService;

import jakarta.annotation.PreDestroy;

/**
 * <b>Le bloc « État connu », préparé à l'avance</b> (F-247 / SF-247-10, cadrage §4.5 ; portes Z9, Z12).
 *
 * <p>Composé <b>hors réplique</b> quand le registre change (abonnement {@link JarvisStateRegistry#onChange}) ou quand il
 * a vieilli (2 min), et gardé en mémoire par compte. Une réplique de Jarvis <b>ne lit jamais le registre</b> : elle
 * prend les lignes prêtes ({@link #ready}) et n'y fait qu'un tri en mémoire par pertinence (sujet en vue, puis client
 * courant, puis les autres clients au poste déconnecté). Cache absent (pod neuf) : la réplique part <b>sans</b> le bloc
 * et une composition est lancée à côté, jamais attendue.</p>
 *
 * <p>Borné en taille fixe quel que soit le volume du registre : <b>20 faits, 2 000 caractères</b> (Z9). Dates et
 * fraîcheur sont calculées par la passerelle (D8) ; au-delà de 24 h, une ligne est marquée « ancien ». Poste connecté :
 * seuls les faits de plus de 15 min entrent, avec « vu à ».</p>
 */
@Component
public class JarvisStateBlock {

    private static final Logger log = LoggerFactory.getLogger(JarvisStateBlock.class);
    static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    public static final int MAX_FACTS = 20;
    public static final int MAX_CHARS = 2_000;
    static final int CANDIDATES_PER_HOST = 20;
    static final Duration STALE = Duration.ofMinutes(2);
    static final Duration OLD = Duration.ofHours(24);
    static final Duration CONNECTED_MIN_AGE = Duration.ofMinutes(15);
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm", Locale.FRENCH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d/MM", Locale.FRENCH);

    private final JarvisStateRegistry registry;
    private final RunnerHostService hosts;
    private final RunnerStatusService status;
    private final JdbcTemplate jdbc;
    private final JarvisStateProperties properties;
    private final Clock clock;
    private final Map<UUID, Prepared> cache = new ConcurrentHashMap<>();
    private final Map<UUID, Boolean> composing = new ConcurrentHashMap<>();
    private final ExecutorService composer;

    public JarvisStateBlock(JarvisStateRegistry registry, RunnerHostService hosts, RunnerStatusService status,
            JdbcTemplate jdbc, JarvisStateProperties properties, Clock clock) {
        this.registry = registry;
        this.hosts = hosts;
        this.status = status;
        this.jdbc = jdbc;
        this.properties = properties;
        this.clock = clock;
        this.composer = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(500), r -> {
            Thread t = new Thread(r, "jarvis-state-block");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        }, new ThreadPoolExecutor.DiscardPolicy());
        registry.onChange((userId, hostId) -> invalidate(userId));
    }

    /** Un client tel que préparé : nom, connexion, coupure, et ses lignes candidates. */
    public record Client(UUID hostId, String name, boolean connected, OffsetDateTime disconnectedAt, String cause,
            int openWaits, List<Line> lines) {
    }

    /** Une ligne prête : l'objet, son texte daté, et ce qui sert au tri et à la garde de fraîcheur (D8). */
    public record Line(UUID hostId, UUID workspaceId, String objectKey, String label, String text,
            OffsetDateTime observedAt, boolean changed, boolean blocked) {
    }

    /** Le bloc préparé d'un compte. */
    public record Prepared(OffsetDateTime composedAt, List<Client> clients) {
    }

    /** Ce qu'une réplique reçoit : le texte du bloc (vide si rien), et les lignes retenues (pour la garde D8). */
    public record Selected(String text, List<Line> lines, long readNanos) {
        public static final Selected EMPTY = new Selected("", List.of(), 0L);
    }

    /**
     * <b>Dans la réplique</b> : le bloc prêt, trié par pertinence en mémoire. Jamais de requête ; cache absent ou vieilli
     * ⇒ composition lancée à côté (le vieilli sert encore, l'absent rend vide).
     */
    public Selected ready(UUID userId, UUID currentHostId, UUID currentWorkspaceId) {
        long t0 = System.nanoTime();
        if (!properties.block() || userId == null) {
            return Selected.EMPTY;
        }
        Prepared p = cache.get(userId);
        if (p == null || Duration.between(p.composedAt(), OffsetDateTime.now(clock)).compareTo(STALE) > 0) {
            composeAsync(userId);
        }
        if (p == null) {
            return new Selected("", List.of(), System.nanoTime() - t0);
        }
        Selected s = select(p, currentHostId, currentWorkspaceId, OffsetDateTime.now(clock));
        long nanos = System.nanoTime() - t0;
        lastReadMs.put(userId, (int) Math.min(600_000, nanos / 1_000_000));
        return new Selected(s.text(), s.lines(), nanos);
    }

    /** La durée de la dernière lecture du bloc prêt d'un compte (mesure {@code state_block_ms}), retirée à la lecture. */
    public Integer takeReadMs(UUID userId) {
        return userId == null ? null : lastReadMs.remove(userId);
    }

    private final Map<UUID, Integer> lastReadMs = new ConcurrentHashMap<>();

    /** Le registre (ou la connexion) a changé : la prochaine réplique relira une composition neuve. */
    public void invalidate(UUID userId) {
        if (userId != null) {
            composeAsync(userId);
        }
    }

    /** Lance une composition à côté (une à la fois par compte). */
    void composeAsync(UUID userId) {
        if (composing.putIfAbsent(userId, Boolean.TRUE) != null) {
            return;
        }
        try {
            composer.execute(() -> {
                try {
                    cache.put(userId, compose(userId));
                } catch (RuntimeException e) {
                    log.debug("Bloc « État connu » : composition en échec ({})", e.getClass().getSimpleName());
                } finally {
                    composing.remove(userId);
                }
            });
        } catch (RuntimeException e) {
            composing.remove(userId);
        }
    }

    /** La composition, hors réplique : les clients du compte, leurs faits récents (index, LIMIT), l'existant compté. */
    public Prepared compose(UUID userId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Client> clients = new ArrayList<>();
        for (RunnerHost h : hosts.list(userId)) {
            if (!userId.equals(h.getUserId()) || !registry.settingsOf(userId, h.getId()).isEnabled()) {
                continue;
            }
            boolean connected;
            try {
                connected = status.statusOf(userId, h).connected();
            } catch (RuntimeException e) {
                connected = false;
            }
            OffsetDateTime[] since = new OffsetDateTime[1];
            String[] cause = new String[1];
            if (!connected) {
                lastDisconnect(userId, h.getId(), since, cause);
            }
            List<Line> lines = lines(registry.recent(userId, h.getId(), CANDIDATES_PER_HOST), now);
            clients.add(new Client(h.getId(), h.getName(), connected, since[0], cause[0], openWaits(userId, h.getId()),
                    lines));
            if (clients.size() >= 12) {
                break;
            }
        }
        return new Prepared(now, List.copyOf(clients));
    }

    /** Les faits regroupés par objet : une ligne par objet, ses attributs ensemble, datée du plus récent. */
    static List<Line> lines(List<JarvisStateRegistry.Fact> facts, OffsetDateTime now) {
        Map<String, List<JarvisStateRegistry.Fact>> byObject = new LinkedHashMap<>();
        for (JarvisStateRegistry.Fact f : facts) {
            byObject.computeIfAbsent(f.objectKey(), k -> new ArrayList<>()).add(f);
        }
        List<Line> out = new ArrayList<>();
        byObject.forEach((key, fs) -> out.add(line(key, fs, now)));
        return out;
    }

    static Line line(String key, List<JarvisStateRegistry.Fact> fs, OffsetDateTime now) {
        Map<String, JarvisStateRegistry.Fact> by = new LinkedHashMap<>();
        fs.forEach(f -> by.putIfAbsent(f.attribute(), f));
        JarvisStateRegistry.Fact newest = fs.stream().max(Comparator.comparing(JarvisStateRegistry.Fact::observedAt))
                .orElseThrow();
        String label = label(key);
        StringBuilder b = new StringBuilder("- ").append(label);
        JarvisStateRegistry.Fact title = by.get("title");
        if (title != null) {
            b.append(" « ").append(cut(title.value(), 60)).append(" »");
        }
        List<String> parts = new ArrayList<>();
        for (String attr : List.of("status", "result", "version", "image", "replicas", "health", "sync", "pipeline",
                "failed_step", "ahead_behind", "state", "assignee", "blocked_by", "last_commit", "tag",
                "schema_version", "account", "value")) {
            JarvisStateRegistry.Fact f = by.get(attr);
            if (f != null) {
                parts.add(attrLabel(attr) + cut(f.value(), 60));
            }
            if (parts.size() >= 3) {
                break;
            }
        }
        b.append(" : ").append(parts.isEmpty() ? "connu" : String.join(", ", parts));
        b.append(" (vu ").append(when(newest.observedAt(), now)).append(", ").append(source(newest.source()))
                .append(")");
        if (Duration.between(newest.observedAt(), now).compareTo(OLD) > 0) {
            b.append(" — ancien");
        }
        JarvisStateRegistry.Fact status = by.containsKey("status") ? by.get("status") : by.get("result");
        boolean changed = status != null && status.previousValue() != null
                && !status.previousValue().equals(status.value());
        if (changed) {
            b.append(" — avant : ").append(cut(status.previousValue(), 40));
        }
        String all = String.join(" ", parts).toLowerCase(Locale.ROOT);
        boolean blocked = by.containsKey("blocked_by") || all.contains("bloqu") || all.contains("failed")
                || all.contains("échec") || all.contains("degraded") || all.contains("outofsync");
        return new Line(newest.hostId(), newest.workspaceId(), key, label, b.toString(), newest.observedAt(), changed,
                blocked);
    }

    /** La sélection, en mémoire : pertinence puis changé, bloqué, récent ; 20 faits et 2 000 caractères au plus. */
    static Selected select(Prepared p, UUID currentHostId, UUID currentWorkspaceId, OffsetDateTime now) {
        List<Line> chosen = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        List<Client> ordered = new ArrayList<>(p.clients());
        ordered.sort(Comparator.comparing((Client c) -> !c.hostId().equals(currentHostId))
                .thenComparing(Client::connected));
        for (Client c : ordered) {
            boolean current = c.hostId().equals(currentHostId);
            if (!current && c.connected()) {
                continue; // groupe 3 : les autres clients, seulement au poste déconnecté
            }
            List<Line> candidates = new ArrayList<>(c.lines().stream()
                    .filter(l -> !c.connected() || Duration.between(l.observedAt(), now)
                            .compareTo(CONNECTED_MIN_AGE) >= 0)
                    .toList());
            candidates.sort(Comparator.comparing((Line l) -> !(currentWorkspaceId != null
                    && currentWorkspaceId.equals(l.workspaceId())))
                    .thenComparing(l -> !l.changed()).thenComparing(l -> !l.blocked())
                    .thenComparing(Line::observedAt, Comparator.reverseOrder()));
            StringBuilder head = new StringBuilder("## État connu — client ").append(c.name()).append('\n');
            if (!c.connected()) {
                head.append("Poste déconnecté").append(c.disconnectedAt() == null ? "" : " depuis "
                        + when(c.disconnectedAt(), now)).append(c.cause() == null ? "" : " (" + cause(c.cause()) + ")")
                        .append(".\n");
            } else {
                head.append("Poste connecté : faits relevés il y a plus de 15 min ; une question qui exige le frais se "
                        + "délègue.\n");
            }
            if (c.openWaits() > 0) {
                head.append(c.openWaits()).append(c.openWaits() > 1 ? " attentes ouvertes" : " attente ouverte")
                        .append(" (état à ").append(HOUR.format(now.atZoneSameInstant(ZONE))).append(")\n");
            }
            StringBuilder body = new StringBuilder();
            for (Line l : candidates) {
                if (chosen.size() >= MAX_FACTS) {
                    break;
                }
                if (text.length() + head.length() + body.length() + l.text().length() + 1 > MAX_CHARS) {
                    break;
                }
                body.append(l.text()).append('\n');
                chosen.add(l);
            }
            if (body.length() == 0 && c.connected()) {
                continue;
            }
            if (text.length() + head.length() + body.length() > MAX_CHARS) {
                break;
            }
            if (body.length() == 0 && c.openWaits() == 0 && c.disconnectedAt() == null) {
                continue;
            }
            text.append(text.length() == 0 ? "" : "\n").append(head).append(body);
        }
        return new Selected(text.toString().strip(), List.copyOf(chosen), 0L);
    }

    private void lastDisconnect(UUID userId, UUID hostId, OffsetDateTime[] since, String[] cause) {
        try {
            jdbc.query("SELECT created_at, cause FROM runner_disconnects WHERE user_id = ? AND host_id = ? "
                    + "ORDER BY created_at DESC LIMIT 1", rs -> {
                        Timestamp t = rs.getTimestamp(1);
                        since[0] = t == null ? null : t.toInstant().atOffset(java.time.ZoneOffset.UTC);
                        cause[0] = rs.getString(2);
                    }, userId, hostId);
        } catch (RuntimeException ignored) {
            // inconnu
        }
    }

    private int openWaits(UUID userId, UUID hostId) {
        try {
            Integer n = jdbc.queryForObject("SELECT count(*) FROM terminal_actions WHERE user_id = ? AND host_id = ? "
                    + "AND status IN ('A_FAIRE', 'DEMANDE')", Integer.class, userId, hostId);
            return n == null ? 0 : n;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** « hier 17:42 », « 08:12 », « 3/11 09:00 » — heure de Paris, calculée par la passerelle (D8). */
    static String when(OffsetDateTime at, OffsetDateTime now) {
        ZonedDateTime a = at.atZoneSameInstant(ZONE);
        ZonedDateTime n = now.atZoneSameInstant(ZONE);
        if (a.toLocalDate().equals(n.toLocalDate())) {
            return "à " + HOUR.format(a);
        }
        if (a.toLocalDate().equals(n.toLocalDate().minusDays(1))) {
            return "hier " + HOUR.format(a);
        }
        return "le " + DAY.format(a) + " " + HOUR.format(a);
    }

    /** « relevé de 17:42 » / « relevé d'hier 17:42 » — la marque de la garde de fraîcheur (D8). */
    public static String freshnessMark(OffsetDateTime at, OffsetDateTime now) {
        String w = when(at, now);
        return w.startsWith("à ") ? "relevé de " + w.substring(2) : w.startsWith("hier") ? "relevé d'" + w
                : "relevé du " + w.substring(3);
    }

    /** Les marques de temps d'une phrase dite (« à 17 h 42 », « hier », « relevé de », « il y a »…). */
    private static final java.util.regex.Pattern TIME_MARK = java.util.regex.Pattern.compile(
            "(?i)\\b\\d{1,2}\\s?(h|:)\\s?\\d{0,2}\\b|\\bhier\\b|\\bavant-hier\\b|\\bce matin\\b|\\bcet après-midi\\b|"
                    + "\\bce soir\\b|relev|\\bil y a\\b|\\bvu\\b|\\bdepuis\\b|\\blundi\\b|\\bmardi\\b|\\bmercredi\\b|"
                    + "\\bjeudi\\b|\\bvendredi\\b|\\bsamedi\\b|\\bdimanche\\b|\\b\\d{1,2}/\\d{1,2}\\b");
    static final Duration FRESHNESS = Duration.ofHours(1);

    /**
     * <b>La garde de fraîcheur</b> (D8) : une phrase dite qui cite un fait de plus d'1 h sans marque de temps reçoit
     * « (relevé de HH:MM) » avant sa ponctuation finale. Jamais d'info ancienne dite comme actuelle.
     */
    public static String withFreshness(String sentence, List<Line> lines, OffsetDateTime now) {
        if (sentence == null || sentence.isBlank() || lines == null || lines.isEmpty()
                || TIME_MARK.matcher(sentence).find()) {
            return sentence;
        }
        String low = sentence.toLowerCase(Locale.ROOT);
        for (Line l : lines) {
            if (Duration.between(l.observedAt(), now).compareTo(FRESHNESS) < 0) {
                continue;
            }
            String id = l.label().replaceFirst("^(MR|Pipeline|Déploiement|Page|Branche|Schéma|Infra) ", "");
            if (low.contains(id.toLowerCase(Locale.ROOT))) {
                String mark = " (" + freshnessMark(l.observedAt(), now) + ")";
                java.util.regex.Matcher end = java.util.regex.Pattern.compile("([.!?…]+)\\s*$").matcher(sentence);
                return end.find() ? sentence.substring(0, end.start()) + mark + end.group(1) : sentence + mark;
            }
        }
        return sentence;
    }

    /** Un libellé court et dicible de la clé ({@code JIRA:PROJ-123} → {@code PROJ-123}). */
    static String label(String key) {
        int i = key.indexOf(':');
        String prefix = i < 0 ? "" : key.substring(0, i);
        String rest = i < 0 ? key : key.substring(i + 1);
        return switch (prefix) {
            case "JIRA" -> rest;
            case "GITLAB", "GITHUB" -> "MR " + rest;
            case "PIPELINE" -> "Pipeline " + rest;
            case "K8S", "HELM", "ARGO" -> "Déploiement " + rest;
            case "CONFLUENCE" -> "Page " + rest;
            case "GIT" -> "Branche " + rest;
            case "DB" -> "Schéma " + rest;
            case "TF" -> "Infra " + rest;
            default -> rest;
        };
    }

    private static String attrLabel(String attr) {
        return switch (attr) {
            case "status", "result", "state" -> "";
            case "version" -> "version ";
            case "image" -> "image ";
            case "replicas" -> "réplicas ";
            case "health" -> "santé ";
            case "sync" -> "synchro ";
            case "pipeline" -> "pipeline ";
            case "failed_step" -> "échec à ";
            case "assignee" -> "assigné à ";
            case "blocked_by" -> "bloqué par ";
            case "last_commit" -> "dernier commit ";
            case "tag" -> "étiquette ";
            case "schema_version" -> "schéma ";
            case "account" -> "compte ";
            default -> "";
        };
    }

    private static String source(String source) {
        return switch (source == null ? "" : source) {
            case "RELEVE" -> "relevé";
            case "VEILLE" -> "veille";
            case "NOTE" -> "constat";
            default -> "tour";
        };
    }

    private static String cause(String cause) {
        return switch (cause) {
            case "ARRET_PROPRE" -> "arrêt propre";
            case "INACTIVITE" -> "inactivité";
            default -> "coupure";
        };
    }

    private static String cut(String s, int max) {
        return s == null ? "" : s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Le cache d'un compte, tel quel (tests). */
    Optional<Prepared> cached(UUID userId) {
        return Optional.ofNullable(cache.get(userId));
    }

    @PreDestroy
    void stop() {
        composer.shutdownNow();
    }
}
