package fr.claudegateway.jarvis.cards;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

import org.springframework.stereotype.Service;

import fr.claudegateway.jarvis.JarvisSpokenWords;
import fr.claudegateway.jarvis.JarvisUsageDay;
import fr.claudegateway.jarvis.JarvisUsageDayRepository;
import fr.claudegateway.jarvis.RenderCounts;
import fr.claudegateway.jarvis.timing.JarvisTurnTiming;
import fr.claudegateway.jarvis.timing.JarvisTurnTimingRepository;

/**
 * <b>La mesure des pancartes</b> (F-248 / SF-248-01, cadrage §6-§7) — les bases des portes prises <b>avant</b> les
 * pancartes : premier jeton et premier son de Jarvis (K1), délai premier mot → pancartes et part des jeux montrés avant
 * le premier mot (K2/K3), classes d'i/s (K4) et compteurs {@code c_*} (valeur). Agrégats seulement, sur les données du
 * compte appelant ({@code user_id} sur les deux lectures).
 */
@Service
public class JarvisCardsMeasureService {

    static final int MAX_DAYS = 90;

    private final JarvisTurnTimingRepository timings;
    private final JarvisUsageDayRepository days;
    private final JarvisCardsProperties properties;
    private final Clock clock;

    public JarvisCardsMeasureService(JarvisTurnTimingRepository timings, JarvisUsageDayRepository days,
            JarvisCardsProperties properties, Clock clock) {
        this.timings = timings;
        this.days = days;
        this.properties = properties;
        this.clock = clock;
    }

    public Measure measure(UUID userId, int window) {
        int d = Math.max(1, Math.min(window, MAX_DAYS));
        OffsetDateTime since = OffsetDateTime.now(clock).minusDays(d);
        List<JarvisTurnTiming> rows = timings.findByUserIdAndCreatedAtGreaterThanEqual(userId, since);
        List<JarvisTurnTiming> withCards = rows.stream().filter(r -> r.getCardsCount() != null
                && r.getCardsCount() > 0).toList();
        long beforeFirstWord = withCards.stream().filter(r -> r.getCardsLagMs() == null).count();
        Map<String, Integer> counts = new TreeMap<>();
        Map<String, Integer> fps = new TreeMap<>();
        for (JarvisUsageDay day : days.findByUserIdAndDayGreaterThanEqualOrderByDayAsc(userId,
                since.toLocalDate())) {
            RenderCounts.parse(day.getRenderCounts()).forEach((k, v) -> {
                if (k.startsWith("c_")) {
                    counts.merge(k, v, Integer::sum);
                } else if (k.startsWith("fps_")) {
                    fps.merge(k, v, Integer::sum);
                }
            });
        }
        int sets = counts.getOrDefault("c_set", 0);
        return new Measure(d, properties.on(), rows.size(),
                new Voice(p(rows, JarvisTurnTiming::getFastFirstTokenMs, 50),
                        p(rows, JarvisTurnTiming::getFastFirstTokenMs, 95),
                        p(rows, JarvisTurnTiming::getFirstSoundMs, 50), p(rows, JarvisTurnTiming::getFirstSoundMs, 95)),
                new Lag(withCards.size(), p(withCards, JarvisTurnTiming::getCardsLagMs, 50),
                        p(withCards, JarvisTurnTiming::getCardsLagMs, 90), beforeFirstWord),
                counts, fps, rate(counts.getOrDefault("c_open", 0), sets),
                rate(counts.getOrDefault("c_close2s", 0), sets));
    }

    private static Double rate(int n, int of) {
        return of == 0 ? null : Math.round(n * 1000.0 / of) / 1000.0;
    }

    private static Integer p(List<JarvisTurnTiming> rows, Function<JarvisTurnTiming, Integer> read, int pct) {
        List<Integer> v = rows.stream().map(read).filter(x -> x != null).sorted().toList();
        return JarvisSpokenWords.percentile(v, pct);
    }

    /** K1 — premier jeton du cerveau rapide et premier son dit, p50/p95 (ms). */
    public record Voice(Integer fastFirstTokenP50, Integer fastFirstTokenP95, Integer firstSoundP50,
            Integer firstSoundP95) {
    }

    /**
     * K2/K3 — délai première phrase jouée → première pancarte.
     *
     * @param beforeFirstWord jeux montrés sans phrase jouée avant eux (délai absent) : la porte K2 exige 0
     */
    public record Lag(int sets, Integer p50, Integer p90, long beforeFirstWord) {
    }

    /**
     * La mesure rendue à l'administrateur.
     *
     * @param openRate    {@code c_open / c_set} (cible ≥ 15 %)
     * @param close2sRate {@code c_close2s / c_set} (« mal montré », cible ≤ 10 %)
     */
    public record Measure(int days, boolean enabled, int turns, Voice voice, Lag lag, Map<String, Integer> counts,
            Map<String, Integer> fps, Double openRate, Double close2sRate) {
    }
}
