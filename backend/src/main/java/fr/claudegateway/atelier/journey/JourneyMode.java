package fr.claudegateway.atelier.journey;

import java.util.Locale;

/**
 * <b>Le mode d'un sujet</b> (F-176 / SF-176-01, décision Q1) : {@link #LIBRE} par défaut — le
 * comportement d'avant F-176, inchangé (Q4) — ou {@link #GUIDE}, où les quatre phases et la porte de
 * plan s'appliquent.
 */
public enum JourneyMode {
    LIBRE, GUIDE;

    /** Lecture tolérante d'un mode reçu ({@code "guidé"}, {@code "guide"}, {@code "GUIDE"}…), ou {@code null}. */
    public static JourneyMode parse(String raw) {
        if (raw == null) {
            return null;
        }
        String v = raw.trim().toUpperCase(Locale.ROOT).replace('É', 'E');
        return switch (v) {
            case "LIBRE", "FREE" -> LIBRE;
            case "GUIDE", "GUIDED" -> GUIDE;
            default -> null;
        };
    }
}
