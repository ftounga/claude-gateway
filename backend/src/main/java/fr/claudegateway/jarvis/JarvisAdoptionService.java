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
        SESSION, SPOKEN, WAKE, IDLE_END;

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
        switch (Event.parse(event)) {
            case SESSION -> usage.addSession(userId);
            case WAKE -> usage.addWake(userId);
            case IDLE_END -> usage.addIdleEnd(userId);
            case SPOKEN -> usage.addSpoken(userId,
                    Math.min(MAX_SPOKEN_SECONDS, Math.max(0L, seconds == null ? 0L : seconds)));
        }
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
        for (JarvisUsageDay d : usage.lastDays(userId, window)) {
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
        return new Measure(window, activeDays, sessions, minutes(spoken), wakes, chars, eur(usd), rows, idleEnds);
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
            long charsSpoken, BigDecimal voiceCostEur, List<Day> perDay, int idleEnds) {
    }
}
