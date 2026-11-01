package fr.claudegateway.jarvis.timing;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.WorkspaceRepository;

/**
 * <b>Le chronomètre du tour vocal</b> (F-233 / SF-233-01, D2) : range une mesure sans contenu par tour vocal et rend,
 * à l'administrateur, p50/p90 par étape sur <b>ses</b> tours.
 *
 * <p>Les marques sont des millisecondes depuis la fin de parole détectée (t0). Rien de ce qui est dit ou lu n'est
 * reçu ni rangé : seulement des durées, des noms de fournisseurs (liste fermée) et des compteurs.</p>
 */
@Service
public class JarvisLatencyService {

    /** Au-delà, une marque est refusée (10 minutes). */
    public static final int MAX_MARK_MS = 600_000;
    /** Au-delà, un compteur est refusé. */
    public static final int MAX_COUNTER = 1_000;
    /** Durée de conservation des mesures. */
    public static final int RETENTION_DAYS = 90;
    static final int MAX_DAYS = 90;

    private static final Pattern PROVIDER = Pattern.compile("^[a-z0-9-]{1,32}$");
    /** SF-233-11 — {@code CONV_*} : le tour a été pris en conversation mains libres. */
    private static final List<String> LISTEN_MODES = List.of("BATCH", "STREAM", "CONV_BATCH", "CONV_STREAM");

    /** Les étapes mesurées, dans l'ordre du tour. */
    static final Map<String, Function<JarvisTurnTiming, Integer>> STEPS = steps();

    private final JarvisTurnTimingRepository repository;
    private final WorkspaceRepository workspaces;
    private final Clock clock;

    public JarvisLatencyService(JarvisTurnTimingRepository repository, WorkspaceRepository workspaces, Clock clock) {
        this.repository = repository;
        this.workspaces = workspaces;
        this.clock = clock;
    }

    private static Map<String, Function<JarvisTurnTiming, Integer>> steps() {
        Map<String, Function<JarvisTurnTiming, Integer>> steps = new LinkedHashMap<>();
        steps.put("speech", JarvisTurnTiming::getSpeechMs);
        steps.put("transcript", JarvisTurnTiming::getTranscriptMs);
        steps.put("reaction", JarvisTurnTiming::getReactionMs);
        steps.put("sent", JarvisTurnTiming::getSentMs);
        steps.put("firstToken", JarvisTurnTiming::getFirstTokenMs);
        steps.put("firstOralSentence", JarvisTurnTiming::getFirstOralSentenceMs);
        steps.put("firstAudioByte", JarvisTurnTiming::getFirstAudioByteMs);
        steps.put("firstSound", JarvisTurnTiming::getFirstSoundMs);
        steps.put("done", JarvisTurnTiming::getDoneMs);
        return java.util.Collections.unmodifiableMap(steps);
    }

    /**
     * Range la mesure d'un tour vocal.
     *
     * @throws IllegalArgumentException une marque, un compteur ou un nom hors bornes
     */
    public void record(UUID userId, TimingReport report) {
        if (report == null) {
            throw new IllegalArgumentException("Mesure vide.");
        }
        String mode = report.listenMode() == null ? "BATCH" : report.listenMode().strip().toUpperCase();
        if (!LISTEN_MODES.contains(mode)) {
            throw new IllegalArgumentException("Mode d'écoute inconnu.");
        }
        UUID workspaceId = report.workspaceId() == null ? null
                : workspaces.findByIdAndUserId(report.workspaceId(), userId).map(w -> w.getId()).orElse(null);
        repository.save(JarvisTurnTiming.builder()
                .userId(userId)
                .workspaceId(workspaceId)
                .createdAt(OffsetDateTime.now(clock))
                .listenMode(mode)
                .sttProvider(provider(report.sttProvider()))
                .ttsProvider(provider(report.ttsProvider()))
                .toolTurn(Boolean.TRUE.equals(report.toolTurn()))
                .speechMs(mark(report.speechMs()))
                .transcriptMs(mark(report.transcriptMs()))
                .reactionMs(mark(report.reactionMs()))
                .sentMs(mark(report.sentMs()))
                .firstTokenMs(mark(report.firstTokenMs()))
                .firstOralSentenceMs(mark(report.firstOralSentenceMs()))
                .firstAudioByteMs(mark(report.firstAudioByteMs()))
                .firstSoundMs(mark(report.firstSoundMs()))
                .doneMs(mark(report.doneMs()))
                .interruptionsDetected(counter(report.interruptionsDetected()))
                .interruptionsRestored(counter(report.interruptionsRestored()))
                .interruptionsConfirmed(counter(report.interruptionsConfirmed()))
                .acknowledgements(counter(report.acknowledgements()))
                .falseTriggers(counter(report.falseTriggers()))
                .build());
    }

    /** p50/p90 par étape et cumul des interruptions, sur les tours du compte depuis {@code days} jours. */
    public Latency latency(UUID userId, int days) {
        int window = Math.max(1, Math.min(days, MAX_DAYS));
        List<JarvisTurnTiming> rows = repository.findByUserIdAndCreatedAtGreaterThanEqual(userId,
                OffsetDateTime.now(clock).minusDays(window));
        List<Step> steps = new ArrayList<>();
        STEPS.forEach((name, read) -> steps.add(step(name, rows.stream().map(read).toList())));
        Map<String, Long> byMode = new LinkedHashMap<>();
        LISTEN_MODES.forEach(m -> byMode.put(m, rows.stream().filter(r -> m.equals(r.getListenMode())).count()));
        Interruptions interruptions = new Interruptions(
                rows.stream().mapToLong(JarvisTurnTiming::getInterruptionsDetected).sum(),
                rows.stream().mapToLong(JarvisTurnTiming::getInterruptionsRestored).sum(),
                rows.stream().mapToLong(JarvisTurnTiming::getInterruptionsConfirmed).sum(),
                rows.stream().mapToLong(JarvisTurnTiming::getAcknowledgements).sum(),
                rows.stream().mapToLong(JarvisTurnTiming::getFalseTriggers).sum());
        return new Latency(window, rows.size(), rows.stream().filter(JarvisTurnTiming::isToolTurn).count(), steps,
                interruptions, byMode);
    }

    /** Purge des mesures de plus de {@link #RETENTION_DAYS} jours. */
    public int purge() {
        return repository.deleteOlderThan(OffsetDateTime.now(clock).minus(Duration.ofDays(RETENTION_DAYS)));
    }

    static Step step(String name, List<Integer> values) {
        List<Integer> present = values.stream().filter(v -> v != null).sorted().toList();
        return new Step(name, present.size(), percentile(present, 50), percentile(present, 90));
    }

    /** Percentile au rang le plus proche sur une liste triée ; {@code null} si vide. */
    static Integer percentile(List<Integer> sorted, int p) {
        if (sorted.isEmpty()) {
            return null;
        }
        int rank = (int) Math.ceil(p / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    private static Integer mark(Integer ms) {
        if (ms == null) {
            return null;
        }
        if (ms < 0 || ms > MAX_MARK_MS) {
            throw new IllegalArgumentException("Marque hors bornes (0 à " + MAX_MARK_MS + " ms).");
        }
        return ms;
    }

    private static int counter(Integer value) {
        if (value == null) {
            return 0;
        }
        if (value < 0 || value > MAX_COUNTER) {
            throw new IllegalArgumentException("Compteur hors bornes (0 à " + MAX_COUNTER + ").");
        }
        return value;
    }

    private static String provider(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String flat = name.strip().toLowerCase();
        if (!PROVIDER.matcher(flat).matches()) {
            throw new IllegalArgumentException("Nom de fournisseur illisible.");
        }
        return flat;
    }

    /** La mesure d'un tour vocal, telle que l'écran la déclare. Aucun contenu. */
    public record TimingReport(UUID workspaceId, String listenMode, String sttProvider, String ttsProvider,
            Boolean toolTurn, Integer speechMs, Integer transcriptMs, Integer reactionMs, Integer sentMs,
            Integer firstTokenMs, Integer firstOralSentenceMs, Integer firstAudioByteMs, Integer firstSoundMs,
            Integer doneMs, Integer interruptionsDetected, Integer interruptionsRestored,
            Integer interruptionsConfirmed, Integer acknowledgements, Integer falseTriggers) {
    }

    /** Une étape : nombre de tours où elle a eu lieu, p50 et p90 en ms (nuls sans mesure). */
    public record Step(String step, int count, Integer p50, Integer p90) {
    }

    /** Le cumul des interruptions de la fenêtre. */
    public record Interruptions(long detected, long restored, long confirmed, long acknowledgements,
            long falseTriggers) {
    }

    /** La mesure rendue à l'administrateur. */
    public record Latency(int days, int turns, long toolTurns, List<Step> steps, Interruptions interruptions,
            Map<String, Long> byListenMode) {
    }
}
