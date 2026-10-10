package fr.claudegateway.notifications;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import fr.claudegateway.push.PushEvent;

/**
 * <b>La règle des préférences</b> (F-185 / SF-185-06), pure : sourdine par événement, heures calmes
 * dans le fuseau de l'utilisateur (une plage peut passer minuit), et D5 — les critiques passent
 * toujours. Valide aussi ce que l'écran envoie.
 */
public record NotificationPreferences(Set<PushEvent> muted, LocalTime quietFrom, LocalTime quietTo, ZoneId zone) {

    public static final String DEFAULT_ZONE = "Europe/Paris";
    private static final Pattern HOUR = Pattern.compile("([01]\\d|2[0-3]):[0-5]\\d");

    /** Rien de coupé, pas d'heures calmes. */
    public static NotificationPreferences defaults() {
        return new NotificationPreferences(Set.of(), null, null, ZoneId.of(DEFAULT_ZONE));
    }

    /** Ce terminal doit-il sonner pour cet événement, à cet instant ? */
    public boolean allowsPush(PushEvent event, Instant now) {
        if (event.critical()) {
            return true;
        }
        if (muted.contains(event)) {
            return false;
        }
        return !inQuietHours(now);
    }

    boolean inQuietHours(Instant now) {
        if (quietFrom == null || quietTo == null) {
            return false;
        }
        LocalTime local = now.atZone(zone).toLocalTime();
        if (quietFrom.isBefore(quietTo)) {
            return !local.isBefore(quietFrom) && local.isBefore(quietTo);
        }
        // La plage passe minuit : 22:00 → 07:00.
        return !local.isBefore(quietFrom) || local.isBefore(quietTo);
    }

    /**
     * Valide ce que l'écran envoie. {@link IllegalArgumentException} au message lisible : code
     * inconnu, critique coupé, heure mal formée, borne seule, bornes égales, fuseau inconnu.
     */
    public static NotificationPreferences parse(List<String> mutedEvents, String quietFrom, String quietTo,
            String timeZone) {
        Set<PushEvent> muted = new LinkedHashSet<>();
        for (String code : mutedEvents == null ? List.<String>of() : mutedEvents) {
            PushEvent event;
            try {
                event = PushEvent.valueOf(code);
            } catch (IllegalArgumentException | NullPointerException e) {
                throw new IllegalArgumentException("Événement inconnu : " + code + ".");
            }
            if (event.critical()) {
                throw new IllegalArgumentException(
                        "« " + event.title() + " » ne peut pas être coupé : il attend une décision.");
            }
            muted.add(event);
        }
        boolean hasFrom = quietFrom != null && !quietFrom.isBlank();
        boolean hasTo = quietTo != null && !quietTo.isBlank();
        if (hasFrom != hasTo) {
            throw new IllegalArgumentException("Les heures calmes demandent un début et une fin.");
        }
        LocalTime from = hasFrom ? hour(quietFrom) : null;
        LocalTime to = hasTo ? hour(quietTo) : null;
        if (from != null && from.equals(to)) {
            throw new IllegalArgumentException("Les heures calmes doivent commencer et finir à des heures différentes.");
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(timeZone == null || timeZone.isBlank() ? DEFAULT_ZONE : timeZone);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Fuseau horaire inconnu : " + timeZone + ".");
        }
        return new NotificationPreferences(Set.copyOf(muted), from, to, zone);
    }

    private static LocalTime hour(String value) {
        if (!HOUR.matcher(value).matches()) {
            throw new IllegalArgumentException("Heure invalide : " + value + " (format HH:MM).");
        }
        try {
            return LocalTime.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Heure invalide : " + value + " (format HH:MM).");
        }
    }

    /** Lecture tolérante d'une ligne en base : un code devenu inconnu est ignoré. */
    static NotificationPreferences fromRow(NotificationPreference row) {
        if (row == null) {
            return defaults();
        }
        Set<PushEvent> muted = row.getMutedEvents() == null ? Set.of()
                : Arrays.stream(row.getMutedEvents().split(","))
                        .map(String::trim)
                        .filter(code -> !code.isEmpty())
                        .flatMap(code -> {
                            try {
                                return java.util.stream.Stream.of(PushEvent.valueOf(code));
                            } catch (IllegalArgumentException e) {
                                return java.util.stream.Stream.empty();
                            }
                        })
                        .filter(event -> !event.critical())
                        .collect(Collectors.toUnmodifiableSet());
        ZoneId zone;
        try {
            zone = ZoneId.of(row.getTimeZone());
        } catch (DateTimeException | NullPointerException e) {
            zone = ZoneId.of(DEFAULT_ZONE);
        }
        LocalTime from = row.getQuietFrom() == null ? null : LocalTime.parse(row.getQuietFrom());
        LocalTime to = row.getQuietTo() == null ? null : LocalTime.parse(row.getQuietTo());
        return new NotificationPreferences(muted, from, to, zone);
    }
}
