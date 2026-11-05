package fr.claudegateway.jarvis.state.releve;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.live.HostTurnActivity;
import fr.claudegateway.atelier.veille.JarvisWatch;
import fr.claudegateway.atelier.veille.JarvisWatchItems;
import fr.claudegateway.atelier.veille.JarvisWatchStateHook;
import fr.claudegateway.jarvis.state.JarvisStateProperties;
import fr.claudegateway.jarvis.state.JarvisStateRegistry;
import fr.claudegateway.jarvis.state.JarvisStateSettings;
import fr.claudegateway.jarvis.state.JarvisStateVocabulary;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * <b>Le relevé côté passerelle</b> (F-247 / SF-247-07, cadrage §4.3, D5, D12, portes Z4/Z5) :
 * <ul>
 * <li><b>la garde</b> d'un relevé ({@code purpose = STATE}) : coupe-circuit {@code APP_JARVIS_STATE_RELEVE_ENABLED},
 * réglage du client, runner qui annonce {@code low_priority} (sinon aucun relevé), <b>aucun tour vivant</b> sur les
 * sujets du poste et <b>aucune commande hors relevé</b> au journal du runner depuis 2 min (requête indexée), et
 * <b>désarmement</b> du jour au-delà de 3 préemptions ;</li>
 * <li><b>les instantanés deviennent des faits</b> (relevés : source {@code RELEVE} ; veilles d'alerte existantes :
 * source {@code VEILLE}), JSON normalisé ⇒ confiance {@code HAUTE}, datés de la fin du passage. Aucune IA.</li>
 * </ul>
 */
@Service
public class JarvisReleveService implements JarvisWatchStateHook {

    private static final Logger log = LoggerFactory.getLogger(JarvisReleveService.class);
    static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    /** Préemptions tolérées par jour et par poste ; au-delà, le relevé se désarme jusqu'au lendemain (D12). */
    static final int MAX_PREEMPTIONS_PER_DAY = 3;
    /** Fenêtre de calme exigée au journal du runner (Z4). */
    static final int QUIET_MINUTES = 2;
    public static final String LOW_PRIORITY = "low_priority";

    private final JarvisStateProperties properties;
    private final JarvisStateRegistry registry;
    private final RunnerHostService hosts;
    private final HostTurnActivity activity;
    private final JdbcTemplate jdbc;

    public JarvisReleveService(JarvisStateProperties properties, JarvisStateRegistry registry, RunnerHostService hosts,
            HostTurnActivity activity, JdbcTemplate jdbc) {
        this.properties = properties;
        this.registry = registry;
        this.hosts = hosts;
        this.activity = activity;
        this.jdbc = jdbc;
    }

    @Override
    public boolean mayRun(JarvisWatch watch, Workspace workspace, OffsetDateTime now) {
        try {
            UUID userId = watch.getUserId();
            UUID hostId = workspace.getHostId();
            if (!properties.releve() || hostId == null) {
                return false;
            }
            JarvisStateSettings s = registry.settingsOf(userId, hostId);
            if (!s.isEnabled() || !s.isReleveEnabled()) {
                return false;
            }
            if (!hosts.declaredCapabilities(hostId).contains(LOW_PRIORITY)) {
                return false; // runner ancien : il ne saurait pas céder la place
            }
            return quiet(userId, hostId, now) && !disarmed(userId, hostId, now);
        } catch (RuntimeException e) {
            log.debug("Relevé : garde en échec, rien ne part ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Z4 (1) — aucun tour vivant ni en attente sur les sujets du poste, <b>quel que soit le pod</b> (F-247 / SF-247-18 :
     * registre de ce pod, demande sans réponse en base, routine ou chantier autonome en cours), et aucune entrée du
     * journal du runner hors relevés et veilles depuis 2 min.
     */
    public boolean quiet(UUID userId, UUID hostId, OffsetDateTime now) {
        return !activity.hostBusy(userId, hostId, Duration.ofMinutes(QUIET_MINUTES), false, now);
    }

    /** D12 — plus de 3 préemptions aujourd'hui sur ce poste : désarmé jusqu'au lendemain. */
    public boolean disarmed(UUID userId, UUID hostId, OffsetDateTime now) {
        LocalDate day = now.atZoneSameInstant(ZONE).toLocalDate();
        Integer n = jdbc.queryForObject("SELECT count(*) FROM jarvis_watch_runs r JOIN jarvis_watches w ON w.id = "
                + "r.watch_id WHERE r.user_id = ? AND w.user_id = ? AND w.purpose = 'STATE' AND r.status = 'YIELDED' "
                + "AND r.error_code = 'preempted' AND r.started_at >= ? AND w.workspace_id IN (SELECT x.id FROM "
                + "workspaces x WHERE x.user_id = ? AND x.host_id = ?)", Integer.class, userId, userId,
                Timestamp.from(day.atStartOfDay(ZONE).toInstant()), userId, hostId);
        return n != null && n > MAX_PREEMPTIONS_PER_DAY;
    }

    @Override
    public void record(JarvisWatch watch, Workspace workspace, List<JarvisWatchItems.Item> items,
            OffsetDateTime observedAt) {
        if (!properties.enabled() || workspace == null || workspace.getHostId() == null || items == null
                || items.isEmpty()) {
            return;
        }
        try {
            if (watch.isState() && !properties.releve()) {
                return;
            }
            registry.record(watch.getUserId(), observations(watch, workspace, items, observedAt));
        } catch (RuntimeException e) {
            log.warn("Relevé : faits non rangés ({})", e.getClass().getSimpleName());
        }
    }

    /** Les faits d'un instantané : statut, assigné, titre, mise à jour de chaque élément. */
    static List<JarvisStateRegistry.Observation> observations(JarvisWatch watch, Workspace workspace,
            List<JarvisWatchItems.Item> items, OffsetDateTime observedAt) {
        String prefix = prefix(watch.getPlatform());
        String kind = kind(watch.getPlatform());
        String source = watch.isState() ? JarvisStateVocabulary.SOURCE_RELEVE : JarvisStateVocabulary.SOURCE_VEILLE;
        String ref = watch.getId() == null ? null : watch.getId().toString();
        List<JarvisStateRegistry.Observation> out = new ArrayList<>();
        for (JarvisWatchItems.Item it : items) {
            if (it.id() == null || it.id().isBlank()) {
                continue;
            }
            String key = prefix + ":" + it.id().strip();
            add(out, workspace, kind, key, "status", it.status(), observedAt, source, ref);
            add(out, workspace, kind, key, "assignee", it.assignee(), observedAt, source, ref);
            add(out, workspace, kind, key, "title", it.title(), observedAt, source, ref);
            add(out, workspace, kind, key, "updated_at", it.updatedAt(), observedAt, source, ref);
        }
        return out;
    }

    private static void add(List<JarvisStateRegistry.Observation> out, Workspace w, String kind, String key,
            String attribute, String value, OffsetDateTime at, String source, String ref) {
        if (value == null || value.isBlank()) {
            return;
        }
        out.add(new JarvisStateRegistry.Observation(w.getHostId(), w.getId(), kind, key, attribute, value, at, source,
                ref, JarvisStateVocabulary.HAUTE));
    }

    /** Le préfixe de clé d'une plateforme, aligné sur l'extraction (JIRA, GITLAB, GITHUB…). */
    static String prefix(String platform) {
        String p = platform == null ? "" : platform.strip().toLowerCase(Locale.ROOT);
        if (p.contains("jira")) {
            return "JIRA";
        }
        if (p.contains("gitlab")) {
            return "GITLAB";
        }
        if (p.contains("github")) {
            return "GITHUB";
        }
        String up = p.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "");
        return up.isEmpty() ? "VEILLE" : up.length() > 16 ? up.substring(0, 16) : up;
    }

    /** La famille d'une plateforme : tickets, revues de code, sinon « autre ». */
    static String kind(String platform) {
        String p = platform == null ? "" : platform.strip().toLowerCase(Locale.ROOT);
        if (p.contains("jira") || p.contains("monday") || p.contains("trello") || p.contains("linear")
                || p.contains("redmine") || p.contains("youtrack")) {
            return "TICKET";
        }
        if (p.contains("gitlab") || p.contains("github")) {
            return "MR";
        }
        return "OTHER";
    }
}
