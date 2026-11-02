package fr.claudegateway.jarvis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import fr.claudegateway.quota.ProviderPricingProperties;

/**
 * <b>La mesure d'adoption du mode Jarvis</b> (F-231 / SF-231-05, D9) : sessions par jour, minutes parlées, réveils
 * par le mot « Jarvis », coût de la voix en euros. Elle <b>conditionne F-232</b> (le compagnon Android) — leçon
 * « mécanisme livré qui dort ».
 *
 * <p>L'écran déclare ses événements ({@link Event}) ; aucun contenu n'est reçu ni gardé. Bornes : une durée parlée
 * hors de 0–600 s est ramenée dans l'intervalle (un extrait de dictée dure au plus 2 min).</p>
 */
@Service
public class JarvisAdoptionService {

    static final long MAX_SPOKEN_SECONDS = 600L;
    static final BigDecimal DEFAULT_USD_TO_EUR = new BigDecimal("0.92");

    private final JarvisUsageService usage;
    private BigDecimal usdToEur = DEFAULT_USD_TO_EUR;

    public JarvisAdoptionService(JarvisUsageService usage) {
        this.usage = usage;
    }

    @Autowired(required = false)
    public void setPricing(ProviderPricingProperties pricing) {
        if (pricing != null && pricing.usdToEur() != null) {
            this.usdToEur = pricing.usdToEur();
        }
    }

    /** Ce que l'écran déclare. */
    public enum Event {
        SESSION, SPOKEN, WAKE, IDLE_END,
        /** F-234 / SF-234-11 — les scènes d'un tour, un toucher de nœud, le mode sobre activé. */
        SCENES, TOUCH, SOBER,
        /** F-236 / SF-236-06 — une session vue par le rendu (3D ou 2D) et ses compteurs. */
        RENDER;

        static Event parse(String raw) {
            if (raw == null) {
                throw new IllegalArgumentException("Événement requis.");
            }
            try {
                return Event.valueOf(raw.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Événement inconnu.");
            }
        }
    }

    /**
     * Inscrit un événement de l'écran Jarvis.
     *
     * @throws IllegalArgumentException événement inconnu
     */
    public void record(UUID userId, String event, Long seconds) {
        record(userId, event, seconds, null, null, null);
    }

    /** Au plus autant de nœuds allumés ou de « Montrer » par tour déclaré. */
    static final int MAX_PER_TURN = 500;

    /**
     * Inscrit un événement, dont {@code SCENES} (F-234 / SF-234-11) : {@code scenes} = compte par identifiant de
     * scène, {@code lit} = nœuds allumés, {@code shows} = demandes « Montrer ». Aucun contenu.
     *
     * @throws IllegalArgumentException événement inconnu, identifiant de scène ou nombre hors bornes
     */
    public void record(UUID userId, String event, Long seconds, java.util.Map<String, Integer> scenes, Integer lit,
            Integer shows) {
        record(userId, event, seconds, scenes, lit, shows, null, null);
    }

    /**
     * Inscrit un événement, dont {@code RENDER} (F-236 / SF-236-06) : {@code mode} = {@code 3d} ou {@code 2d},
     * {@code render} = compteurs du vocabulaire fermé de {@link RenderCounts}. Aucun contenu.
     *
     * @throws IllegalArgumentException événement inconnu, mode ou compteur hors vocabulaire / bornes
     */
    public void record(UUID userId, String event, Long seconds, java.util.Map<String, Integer> scenes, Integer lit,
            Integer shows, String mode, java.util.Map<String, Integer> render) {
        switch (Event.parse(event)) {
            case RENDER -> usage.addRender(userId, parseMode(mode), RenderCounts.validate(render));
            case SCENES -> usage.addScenes(userId, SceneCounts.validate(scenes), bounded(lit), bounded(shows));
            case TOUCH -> usage.addNodeTouch(userId);
            case SOBER -> usage.addSoberOn(userId);
            case SESSION -> usage.addSession(userId);
            case WAKE -> usage.addWake(userId);
            case IDLE_END -> usage.addIdleEnd(userId);
            case SPOKEN -> usage.addSpoken(userId,
                    Math.min(MAX_SPOKEN_SECONDS, Math.max(0L, seconds == null ? 0L : seconds)));
        }
    }

    private static boolean parseMode(String mode) {
        if ("3d".equalsIgnoreCase(mode == null ? null : mode.strip())) {
            return true;
        }
        if ("2d".equalsIgnoreCase(mode == null ? null : mode.strip())) {
            return false;
        }
        throw new IllegalArgumentException("Mode de rendu inconnu.");
    }

    private static int bounded(Integer n) {
        if (n == null) {
            return 0;
        }
        if (n < 0 || n > MAX_PER_TURN) {
            throw new IllegalArgumentException("Nombre hors bornes.");
        }
        return n;
    }

    /** La mesure sur {@code days} jours (1 à 90), pour ce compte. */
    public Measure measure(UUID userId, int days) {
        int window = Math.max(1, Math.min(days, 90));
        List<Day> rows = new ArrayList<>();
        int sessions = 0;
        long spoken = 0;
        int wakes = 0;
        int idleEnds = 0;
        long chars = 0;
        BigDecimal usd = BigDecimal.ZERO;
        int scenesShown = 0;
        int scenesGeneric = 0;
        int nodesLit = 0;
        int nodeTouches = 0;
        int soberOn = 0;
        int showRequests = 0;
        java.util.Map<String, Integer> families = new java.util.TreeMap<>();
        int render3d = 0;
        int render2d = 0;
        java.util.Map<String, Integer> renderCounts = new java.util.TreeMap<>();
        for (JarvisUsageDay d : usage.lastDays(userId, window)) {
            render3d += d.getRender3d();
            render2d += d.getRender2d();
            RenderCounts.parse(d.getRenderCounts()).forEach((k, v) -> renderCounts.merge(k, v, Integer::sum));
            scenesShown += d.getScenesShown();
            scenesGeneric += d.getScenesGeneric();
            nodesLit += d.getNodesLit();
            nodeTouches += d.getNodeTouches();
            soberOn += d.getSoberOn();
            showRequests += d.getShowRequests();
            SceneCounts.parse(d.getSceneCounts()).forEach((k, v) -> families.merge(k, v, Integer::sum));
            rows.add(new Day(d.getDay(), d.getSessions(), minutes(d.getSpokenSeconds()), d.getWakes(),
                    d.getCharsSpoken(), eur(d.totalCostUsd())));
            sessions += d.getSessions();
            spoken += d.getSpokenSeconds();
            wakes += d.getWakes();
            idleEnds += d.getConversationIdleEnds();
            chars += d.getCharsSpoken();
            usd = usd.add(d.totalCostUsd());
        }
        int activeDays = (int) rows.stream().filter(r -> r.sessions() > 0).count();
        BigDecimal genericShare = scenesShown == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(100L * scenesGeneric).divide(BigDecimal.valueOf(scenesShown), 1, RoundingMode.HALF_UP);
        int renderSessions = render3d + render2d;
        BigDecimal share3d = renderSessions == 0 ? BigDecimal.ZERO
                : BigDecimal.valueOf(100L * render3d).divide(BigDecimal.valueOf(renderSessions), 1, RoundingMode.HALF_UP);
        return new Measure(window, activeDays, sessions, minutes(spoken), wakes, chars, eur(usd), rows, idleEnds,
                new SceneMeasure(scenesShown, genericShare, families, nodesLit, nodeTouches, soberOn, showRequests),
                new RenderMeasure(render3d, render2d, share3d, renderCounts));
    }

    private BigDecimal eur(BigDecimal usd) {
        return (usd == null ? BigDecimal.ZERO : usd).multiply(usdToEur).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal minutes(long seconds) {
        return BigDecimal.valueOf(seconds).divide(BigDecimal.valueOf(60), 1, RoundingMode.HALF_UP);
    }

    /** Une journée de la mesure. */
    public record Day(LocalDate day, int sessions, BigDecimal spokenMinutes, int wakes, long charsSpoken,
            BigDecimal voiceCostEur) {
    }

    /** La mesure d'adoption : totaux de la fenêtre et détail par jour. */
    public record Measure(int days, int activeDays, int sessions, BigDecimal spokenMinutes, int wakes,
            long charsSpoken, BigDecimal voiceCostEur, List<Day> perDay, int idleEnds, SceneMeasure scenes,
            RenderMeasure render) {
    }

    /**
     * F-236 / SF-236-06 (cadrage §18) — le rendu : sessions en 3D / en 2D, part de la 3D (%), et le détail (raisons du
     * repli, secondes par niveau, classes d'i/s p50, gestes, touchers par type, passages de cerveau, entrées sautées,
     * bascules 3D → 2D). Aucun contenu.
     */
    public record RenderMeasure(int sessions3d, int sessions2d, BigDecimal share3dPct,
            java.util.Map<String, Integer> counts) {
    }

    /**
     * F-234 / SF-234-11 (D16) — l'intérêt de l'écran vivant : scènes montrées, part de la générique (%), détail par
     * scène, nœuds allumés, touchers de nœud, mode sobre activé, demandes « Montrer ». Aucun contenu.
     */
    public record SceneMeasure(int shown, BigDecimal genericSharePct, java.util.Map<String, Integer> perScene,
            int nodesLit, int nodeTouches, int soberOn, int showRequests) {
    }
}
