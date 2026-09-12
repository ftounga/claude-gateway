package fr.claudegateway.runner.teams;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>Ce que l'agent demande</b>, traduit en fenêtre de lecture (F-88 / SF-88-01, décision
 * <b>D4</b> du cadrage).
 *
 * <p>Le plafond existe parce que c'est le <b>défilement</b> qui décide du temps et du risque de
 * casse. Il est donc : <b>annoncé</b> (dans la description de l'outil et dans le résultat),
 * <b>négociable</b> (« remonte jusqu'au 1<sup>er</sup> septembre » doit marcher), et <b>jamais
 * silencieux</b> — tout ce qui est ramené dans les bornes est <b>dit</b>.</p>
 *
 * <p>Les formes acceptées pour une borne : une date ISO (« 2026-09-01 »), un instant ISO
 * (« 2026-09-01T08:00:00Z »), ou une durée en arrière (« 7d », « 7 jours », « 36h »,
 * « 2 semaines »). Une forme illisible n'échoue pas : elle retombe sur le défaut, <b>et le dit</b> —
 * faire échouer un outil de lecture sur une faute de frappe de l'agent ferait perdre le tour.</p>
 */
final class TeamsAsk {

    /** Plafond haut, négociable jusque-là : au-delà, le défilement devient déraisonnable. */
    static final int MAX_CAP = 2_000;

    private static final Pattern RELATIVE = Pattern.compile(
            "^(\\d{1,4})\\s*(m|min|minute|minutes|h|heure|heures|hour|hours|d|j|jour|jours|day|days"
                    + "|w|semaine|semaines|week|weeks)$");

    private final TeamsReadWindow window;
    private final List<String> notes;

    private TeamsAsk(TeamsReadWindow window, List<String> notes) {
        this.window = window;
        this.notes = List.copyOf(notes);
    }

    TeamsReadWindow window() {
        return window;
    }

    /** Ce qui a été ramené dans les bornes ou remplacé par un défaut — à dire dans le résultat. */
    List<String> notes() {
        return notes;
    }

    static TeamsAsk of(JsonNode input, Instant now) {
        List<String> notes = new ArrayList<>();
        Instant reference = now == null ? Instant.now() : now;

        Instant to = parse(text(input, "to"), reference, notes, "to");
        if (to == null) {
            to = reference;
        }
        Instant from = parse(text(input, "from"), reference, notes, "from");
        if (from == null) {
            from = to.minus(Duration.ofDays(TeamsReadWindow.DEFAULT_DAYS));
            if (!text(input, "from").isBlank()) {
                notes.add("Période de départ non comprise : le plafond par défaut de "
                        + TeamsReadWindow.DEFAULT_DAYS + " jours a été appliqué.");
            }
        }
        if (from.isAfter(to)) {
            Instant swap = from;
            from = to;
            to = swap;
            notes.add("Les deux bornes étaient à l'envers : elles ont été remises dans l'ordre.");
        }

        int cap = (int) number(input, TeamsReadWindow.DEFAULT_MAX_MESSAGES, "max_messages", "limit");
        if (cap <= 0) {
            cap = TeamsReadWindow.DEFAULT_MAX_MESSAGES;
        }
        if (cap > MAX_CAP) {
            notes.add("Plafond demandé ramené à " + MAX_CAP + " messages : au-delà, la remontée "
                    + "devient trop longue pour un tour.");
            cap = MAX_CAP;
        }
        return new TeamsAsk(new TeamsReadWindow(from, to, null, null, cap, false, false), notes);
    }

    /** La fenêtre par défaut, sans demande : les sept derniers jours (D4). */
    static TeamsAsk standard(Instant now) {
        return new TeamsAsk(TeamsReadWindow.standard(now == null ? Instant.now() : now), List.of());
    }

    // ------------------------------------------------------------------ lecture des bornes

    private static Instant parse(String raw, Instant reference, List<String> notes, String field) {
        String value = raw == null ? "" : raw.strip();
        if (value.isEmpty()) {
            return null;
        }
        Matcher relative = RELATIVE.matcher(value.toLowerCase(Locale.FRENCH));
        if (relative.matches()) {
            long amount = Long.parseLong(relative.group(1));
            return reference.minus(durationOf(relative.group(2), amount));
        }
        try {
            return Instant.parse(value);
        } catch (RuntimeException ignored) {
            // pas un instant : peut-être une date seule
        }
        try {
            return LocalDate.parse(value).atStartOfDay(ZoneId.systemDefault()).toInstant();
        } catch (RuntimeException ignored) {
            if (!"from".equals(field)) {
                notes.add("Borne « " + field + " » non comprise : elle a été ignorée.");
            }
            return null;
        }
    }

    private static Duration durationOf(String unit, long amount) {
        return switch (unit) {
            case "m", "min", "minute", "minutes" -> Duration.ofMinutes(amount);
            case "h", "heure", "heures", "hour", "hours" -> Duration.ofHours(amount);
            case "w", "semaine", "semaines", "week", "weeks" -> Duration.ofDays(amount * 7);
            default -> Duration.ofDays(amount);
        };
    }

    static String text(JsonNode input, String... fields) {
        if (input == null) {
            return "";
        }
        for (String field : fields) {
            JsonNode value = input.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                return value.asText().strip();
            }
        }
        return "";
    }

    static long number(JsonNode input, long fallback, String... fields) {
        if (input == null) {
            return fallback;
        }
        for (String field : fields) {
            JsonNode value = input.get(field);
            if (value != null && value.isNumber()) {
                return value.asLong();
            }
            if (value != null && value.isTextual()) {
                try {
                    return Long.parseLong(value.asText().strip());
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }
}
