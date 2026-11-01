package fr.claudegateway.atelier.veille;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import fr.claudegateway.atelier.routine.RoutineCadence;
import fr.claudegateway.atelier.routine.RoutineSlots;

/**
 * <b>Les créneaux d'une veille</b> (F-230, D1/D7) : plusieurs heures par jour, <b>du lundi au vendredi</b>, dans
 * le fuseau de la veille. Calcul pur, délégué aux créneaux des routines ({@link RoutineSlots}, rythme
 * {@link RoutineCadence#WEEKDAYS}) : le planificateur n'est pas dupliqué.
 */
public final class JarvisWatchSlots {

    /** Heures au plus par veille. */
    public static final int MAX_TIMES = 6;

    private JarvisWatchSlots() {
    }

    /** Les heures rangées ({@code "09:00,14:00"}) en liste ; illisible ⇒ ignorée. */
    public static List<String> times(String stored) {
        List<String> times = new ArrayList<>();
        if (stored == null) {
            return times;
        }
        for (String part : stored.split(",")) {
            RoutineSlots.parseTime(part).ifPresent(time -> times.add(format(time)));
        }
        return times;
    }

    /** {@code HH:mm}. */
    public static String format(LocalTime time) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", time.getHour(), time.getMinute());
    }

    /** Le créneau dû (le dernier passé, toutes heures confondues), ou vide. */
    public static Optional<OffsetDateTime> dueSlot(JarvisWatch watch, OffsetDateTime now) {
        Optional<ZoneId> zone = RoutineSlots.parseZone(watch.getTimeZone());
        if (zone.isEmpty()) {
            return Optional.empty();
        }
        OffsetDateTime best = null;
        for (String time : times(watch.getRunTimes())) {
            Optional<OffsetDateTime> slot = RoutineSlots.dueSlot(now, RoutineCadence.WEEKDAYS, null,
                    LocalTime.parse(time), zone.get());
            if (slot.isPresent() && (best == null || slot.get().isAfter(best))) {
                best = slot.get();
            }
        }
        return Optional.ofNullable(best);
    }

    /** Le prochain créneau strictement après maintenant, toutes heures confondues, ou vide. */
    public static Optional<OffsetDateTime> nextSlot(JarvisWatch watch, OffsetDateTime now) {
        Optional<ZoneId> zone = RoutineSlots.parseZone(watch.getTimeZone());
        if (zone.isEmpty()) {
            return Optional.empty();
        }
        OffsetDateTime best = null;
        for (String time : times(watch.getRunTimes())) {
            Optional<OffsetDateTime> slot = RoutineSlots.nextSlot(now, RoutineCadence.WEEKDAYS, null,
                    LocalTime.parse(time), zone.get());
            if (slot.isPresent() && (best == null || slot.get().isBefore(best))) {
                best = slot.get();
            }
        }
        return Optional.ofNullable(best);
    }
}
