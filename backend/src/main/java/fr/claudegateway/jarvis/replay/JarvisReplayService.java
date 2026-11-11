package fr.claudegateway.jarvis.replay;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.jarvis.capture.JarvisAudioCaptureRepository;
import fr.claudegateway.jarvis.replay.JarvisReplay.Replay;
import fr.claudegateway.jarvis.replay.JarvisReplay.Session;
import fr.claudegateway.jarvis.replay.JarvisReplay.Thresholds;
import fr.claudegateway.jarvis.replay.JarvisReplay.Turn;
import fr.claudegateway.jarvis.thread.JarvisThreadService;
import fr.claudegateway.jarvis.timing.JarvisTurnTimingRepository;
import fr.claudegateway.jarvis.trace.JarvisListenTraceRepository;
import fr.claudegateway.jarvis.trace.JarvisListenTraceService;

/**
 * <b>Le rejeu de séance Jarvis</b> (F-255 / SF-255-02) : lit, sous le compte, le journal d'écoute, les mesures, les
 * captures et les messages d'une fenêtre, et les assemble tour par tour ({@link JarvisReplayAssembler}). Rien n'est
 * écrit. Le journal d'écoute est gardé {@value JarvisListenTraceService#RETENTION_DAYS} jours : au-delà, il n'y a plus
 * de séance à rejouer.
 */
@Service
public class JarvisReplayService {

    /** Deux tours séparés de plus que cela appartiennent à deux séances. */
    public static final Duration SESSION_GAP = Duration.ofMinutes(10);
    /** Une séance rejouée d'un trait : au plus cette durée. */
    public static final Duration MAX_SPAN = Duration.ofHours(6);
    /** Avant la fenêtre : de quoi retrouver l'ouverture de la page. */
    static final Duration LEAD = Duration.ofMinutes(30);
    /** Après la fenêtre : de quoi retrouver les réponses des délégations. */
    static final Duration TAIL = Duration.ofMinutes(25);

    private final JarvisListenTraceRepository traces;
    private final JarvisTurnTimingRepository timings;
    private final JarvisAudioCaptureRepository captures;
    private final JarvisReplayMessageRepository messages;
    private final WorkspaceRepository workspaces;
    private final JarvisThreadService threads;
    private final Clock clock;

    public JarvisReplayService(JarvisListenTraceRepository traces, JarvisTurnTimingRepository timings,
            JarvisAudioCaptureRepository captures, JarvisReplayMessageRepository messages,
            WorkspaceRepository workspaces, JarvisThreadService threads, Clock clock) {
        this.traces = traces;
        this.timings = timings;
        this.captures = captures;
        this.messages = messages;
        this.workspaces = workspaces;
        this.threads = threads;
        this.clock = clock;
    }

    /** Les seuils en vigueur (rendus avec chaque rejeu). */
    public static Thresholds thresholds() {
        return new Thresholds(JarvisReplayVerdicts.SLOW_FIRST_SOUND_MS, JarvisReplayVerdicts.REPEAT_WINDOW,
                SESSION_GAP.toMinutes());
    }

    /** Les séances du compte sur {@code days} jours (au plus la rétention du journal), les plus récentes d'abord. */
    @Transactional(readOnly = true)
    public List<Session> sessions(UUID userId, int days) {
        int window = Math.max(1, Math.min(days, JarvisListenTraceService.RETENTION_DAYS));
        OffsetDateTime to = OffsetDateTime.now(clock);
        OffsetDateTime from = to.minusDays(window);
        List<Turn> turns = load(userId, from, to);
        List<Session> sessions = new ArrayList<>();
        for (List<Turn> group : group(turns)) {
            sessions.add(new Session(group.get(0).at(), group.get(group.size() - 1).at(), pages(group), group.size(),
                    JarvisReplayVerdicts.bilan(group)));
        }
        return sessions.reversed();
    }

    /**
     * Le rejeu des tours du compte entre {@code from} et {@code to} (inclus).
     *
     * @throws IllegalArgumentException fenêtre absente, inversée ou plus longue que {@link #MAX_SPAN}
     */
    @Transactional(readOnly = true)
    public Replay replay(UUID userId, OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null || to.isBefore(from) || Duration.between(from, to).compareTo(MAX_SPAN) > 0) {
            throw new IllegalArgumentException("Fenêtre de séance invalide.");
        }
        List<Turn> turns = load(userId, from, to.plusSeconds(1)).stream()
                .filter(t -> !t.at().isBefore(from) && !t.at().isAfter(to)).toList();
        return new Replay(from, to, pages(turns), turns, turns.isEmpty() ? null : JarvisReplayVerdicts.bilan(turns),
                thresholds());
    }

    private List<Turn> load(UUID userId, OffsetDateTime from, OffsetDateTime to) {
        OffsetDateTime start = from.minus(LEAD);
        OffsetDateTime end = to.plus(TAIL);
        Map<UUID, String> names = new HashMap<>();
        workspaces.findByUserIdOrderByCreatedAtDesc(userId).forEach(w -> names.put(w.getId(), w.getName()));
        UUID thread = threads.find(userId).map(Workspace::getId).orElse(null);
        List<Turn> turns = JarvisReplayAssembler.assemble(new JarvisReplayAssembler.Sources(
                traces.findByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(userId, start, end),
                timings.findByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(userId, start, end),
                captures.findByUserIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(userId, start, end),
                messages.findJarvisExchanges(userId, start, end), thread, names));
        List<Turn> inside = turns.stream().filter(t -> !t.at().isBefore(from) && t.at().isBefore(to)).toList();
        return JarvisReplayVerdicts.judge(inside, names, thread);
    }

    /** Les tours regroupés en séances (écart &gt; {@link #SESSION_GAP}). */
    static List<List<Turn>> group(List<Turn> turns) {
        List<List<Turn>> groups = new ArrayList<>();
        List<Turn> current = new ArrayList<>();
        for (Turn t : turns) {
            if (!current.isEmpty()
                    && Duration.between(current.get(current.size() - 1).at(), t.at()).compareTo(SESSION_GAP) > 0) {
                groups.add(current);
                current = new ArrayList<>();
            }
            current.add(t);
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    private static int pages(List<Turn> turns) {
        return new HashSet<>(turns.stream().map(Turn::pageId).toList()).size();
    }
}
