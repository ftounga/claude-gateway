package fr.claudegateway.jarvis.fastbrain;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.autonomy.AutonomyRunStatus;
import fr.claudegateway.atelier.autonomy.ChantierAutonomyRun;
import fr.claudegateway.atelier.autonomy.ChantierAutonomyRunRepository;

/**
 * <b>Les travaux vivants de Jarvis</b> (F-270 / SF-270-01, D5) — la lecture de l'écran, identique depuis n'importe quel
 * pod : les délégations et tours directs du registre partagé ({@link JarvisDelegationStore}, F-272) et les courses
 * autonomes F-219 (lues dans leur table, jamais recopiées). Un travail fini reste visible {@link #ENDED_VISIBLE} (la perle
 * se pose puis s'éteint) ; un travail qui attend un « oui », {@link #WAITING_VISIBLE}.
 *
 * <p><b>Isolation</b> : tout est lu sous {@code user_id}. <b>Best-effort</b> : une panne rend une liste vide.</p>
 */
@Service
public class JarvisWorksService {

    private static final Logger log = LoggerFactory.getLogger(JarvisWorksService.class);
    static final Duration ENDED_VISIBLE = Duration.ofSeconds(20);
    static final Duration WAITING_VISIBLE = Duration.ofMinutes(2);
    /** D8 : 8 délégations vivantes + 4 courses par compte. */
    static final int MAX_WORKS = 12;
    static final int RECENT_COURSES = 8;

    private final JarvisDelegationStore store;
    private final WorkspaceRepository workspaces;
    private final ChantierAutonomyRunRepository runs;
    private final Clock clock;

    public JarvisWorksService(JarvisDelegationStore store, WorkspaceRepository workspaces,
            ChantierAutonomyRunRepository runs, Clock clock) {
        this.store = store;
        this.workspaces = workspaces;
        this.runs = runs;
        this.clock = clock;
    }

    /** Les travaux du compte : vivants, finis depuis peu, en attente d'un « oui » ; du plus ancien au plus récent. */
    public List<JarvisWorks.Work> list(UUID userId) {
        Instant now = clock.instant();
        List<JarvisWorks.Work> out = new ArrayList<>();
        for (JarvisDelegation d : store.recent(userId, JarvisDelegationRegistry.MAX_AGE, 50)) {
            JarvisWorks.Work w = ofDelegation(d, now);
            if (w != null) {
                out.add(w);
            }
        }
        out.addAll(courses(userId, now));
        out.sort(Comparator.comparing(JarvisWorks.Work::since));
        return out.size() <= MAX_WORKS ? out : out.subList(out.size() - MAX_WORKS, out.size());
    }

    /** Le résultat d'une annonce de tour direct. */
    public enum Announce { CREATED, NOT_FOUND, CONFLICT, FAILED }

    /** F-270 / SF-270-01 — l'écran annonce un tour direct né à la voix, sur un sujet du compte. */
    public Announce announce(UUID userId, UUID id, UUID subjectId, Integer chantier, String label) {
        if (userId == null || id == null || subjectId == null || (chantier != null && chantier < 1)) {
            return Announce.NOT_FOUND;
        }
        Workspace subject = workspaces.findByIdAndUserId(subjectId, userId).orElse(null);
        if (subject == null) {
            return Announce.NOT_FOUND;
        }
        String name = subject.isJarvisThread() ? "" : subject.getName();
        return switch (store.direct(userId, id, subjectId, name, chantier, label)) {
            case CREATED -> Announce.CREATED;
            case CONFLICT -> Announce.CONFLICT;
            case FAILED -> Announce.FAILED;
        };
    }

    // ------------------------------------------------------------------ F-270 / SF-270-04 : l'enchaînement

    /** Le poste du sujet est-il éteint ? Facultatif (sans lui : jamais éteint). */
    private fr.claudegateway.jarvis.state.JarvisStateTurnMeter hosts;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHosts(fr.claudegateway.jarvis.state.JarvisStateTurnMeter hosts) {
        this.hosts = hosts;
    }

    /** L'issue d'une libération : partie, poste éteint (abandonnée), introuvable. */
    public record Release(Status status, String host) {
        public enum Status { RELEASED, HOST_OFFLINE, NOT_FOUND }
    }

    /**
     * La commande enchaînée part (le travail devant elle est fini) : poste éteint ⇒ abandonnée et dite par l'écran,
     * jamais gardée (décision PO du 15/11) ; sinon elle redevient une offre que le tour du fil prendra.
     */
    public Release release(UUID userId, UUID delegationId) {
        JarvisDelegation d = store.find(userId, delegationId);
        if (d == null || d.getFinishedAt() != null || !JarvisWorkPhase.QUEUED.name().equals(d.getPhase())) {
            return new Release(Release.Status.NOT_FOUND, null);
        }
        Workspace subject = workspaces.findByIdAndUserId(d.getSubjectId(), userId).orElse(null);
        if (subject == null) {
            store.finished(userId, delegationId, JarvisWorkPhase.CANCELLED);
            return new Release(Release.Status.NOT_FOUND, null);
        }
        if (offline(userId, subject)) {
            store.finished(userId, delegationId, JarvisWorkPhase.CANCELLED);
            return new Release(Release.Status.HOST_OFFLINE, hostName(subject));
        }
        return new Release(store.release(userId, delegationId) ? Release.Status.RELEASED : Release.Status.NOT_FOUND,
                null);
    }

    /** Annule une commande enchaînée ou un tour direct pas encore pris (« arrête recette ») ; vrai si annulé. */
    public boolean cancel(UUID userId, UUID delegationId) {
        JarvisDelegation d = store.find(userId, delegationId);
        if (d == null || d.getFinishedAt() != null || d.getTakenAt() != null) {
            return false;
        }
        store.finished(userId, delegationId, JarvisWorkPhase.CANCELLED);
        return true;
    }

    private boolean offline(UUID userId, Workspace subject) {
        try {
            return hosts != null && subject.getHostId() != null
                    && Boolean.TRUE.equals(hosts.hostOffline(userId, subject));
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Le nom du poste du sujet, pour la phrase dite (« Le poste CAGIP est éteint »). */
    private String hostName(Workspace subject) {
        return hostNames == null || subject.getHostId() == null ? null
                : hostNames.findById(subject.getHostId()).map(fr.claudegateway.runner.host.RunnerHost::getName)
                        .orElse(null);
    }

    private fr.claudegateway.runner.host.RunnerHostRepository hostNames;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHostNames(fr.claudegateway.runner.host.RunnerHostRepository hostNames) {
        this.hostNames = hostNames;
    }

    private JarvisWorks.Work ofDelegation(JarvisDelegation d, Instant now) {
        Instant since = d.getCreatedAt().toInstant();
        Instant ended = d.getFinishedAt() == null ? null : d.getFinishedAt().toInstant();
        JarvisWorkPhase phase;
        if (ended == null) {
            boolean queued = JarvisWorkPhase.QUEUED.name().equals(d.getPhase());
            if (!queued && d.getTakenAt() == null && since.isBefore(now.minus(JarvisDelegationRegistry.GRACE))) {
                return null; // jamais pris : le balayage la clôt (comme running())
            }
            phase = JarvisWorkPhase.of(d.getPhase(), JarvisWorkPhase.THINKING);
        } else {
            phase = JarvisWorkPhase.of(d.getOutcome(), JarvisWorkPhase.DONE);
            Duration visible = phase == JarvisWorkPhase.WAITING_YES ? WAITING_VISIBLE : ENDED_VISIBLE;
            if (ended.isBefore(now.minus(visible))) {
                return null;
            }
        }
        JarvisWorks.Origin origin = JarvisDelegation.ORIGIN_DIRECT.equals(d.getOrigin()) ? JarvisWorks.Origin.DIRECT
                : JarvisWorks.Origin.DELEGATION;
        String label = d.getLabel() == null ? "" : d.getLabel();
        return new JarvisWorks.Work(d.getDelegationId().toString(), origin, d.getSubjectId(),
                d.getSubjectName() == null ? "" : d.getSubjectName(), d.getChantier(),
                JarvisWorks.shortName(label, d.getChantier(), d.getSubjectName()), label, phase, since, ended);
    }

    private List<JarvisWorks.Work> courses(UUID userId, Instant now) {
        try {
            List<ChantierAutonomyRun> rows = runs.findByUserIdOrderByStartedAtDesc(userId,
                    PageRequest.of(0, RECENT_COURSES));
            Map<UUID, String> names = new HashMap<>();
            List<JarvisWorks.Work> out = new ArrayList<>();
            for (ChantierAutonomyRun r : rows) {
                JarvisWorkPhase phase = coursePhase(r.getStatus());
                Instant ended = r.getFinishedAt() == null ? null : r.getFinishedAt().toInstant();
                if (phase == null || (r.getStatus() != AutonomyRunStatus.RUNNING
                        && (ended == null || ended.isBefore(now.minus(ENDED_VISIBLE))))) {
                    continue;
                }
                String subject = names.computeIfAbsent(r.getWorkspaceId(), id -> workspaces
                        .findByIdAndUserId(id, userId).map(Workspace::getName).orElse(""));
                String title = r.getChantierTitle() == null ? "" : r.getChantierTitle();
                out.add(new JarvisWorks.Work("course:" + r.getId(), JarvisWorks.Origin.COURSE, r.getWorkspaceId(),
                        subject, r.getChantierNumber(), JarvisWorks.shortName(title, r.getChantierNumber(), subject),
                        title, phase, r.getStartedAt().toInstant(),
                        r.getStatus() == AutonomyRunStatus.RUNNING ? null : ended));
            }
            return out;
        } catch (RuntimeException e) {
            log.debug("Courses autonomes illisibles ({})", e.getClass().getSimpleName());
            return List.of();
        }
    }

    static JarvisWorkPhase coursePhase(AutonomyRunStatus status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case RUNNING -> JarvisWorkPhase.EXECUTING;
            case DONE, PARTIAL -> JarvisWorkPhase.DONE;
            case BLOCKED, FAILED -> JarvisWorkPhase.FAILED;
            case STOPPED -> JarvisWorkPhase.CANCELLED;
        };
    }
}
