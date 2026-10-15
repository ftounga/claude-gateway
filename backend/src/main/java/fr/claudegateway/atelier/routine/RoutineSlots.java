package fr.claudegateway.atelier.routine;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * <b>Les créneaux d'une routine</b> (F-183 / SF-183-01) : quand tombe une routine, dans le fuseau de
 * <b>son poste</b>. Calcul pur, sans horloge ni base — même patron que les créneaux du soir du Radar
 * (F-100), étendu aux rythmes jours ouvrés et hebdomadaire.
 */
public final class RoutineSlots {

    /** Une semaine et un jour : tout rythme a au moins un créneau dans cette fenêtre. */
    private static final int SEARCH_DAYS = 8;

    private RoutineSlots() {
    }

    /** {@code HH:mm} lisible, ou vide. */
    public static Optional<LocalTime> parseTime(String value) {
        if (value == null || !value.strip().matches("([01][0-9]|2[0-3]):[0-5][0-9]")) {
            return Optional.empty();
        }
        return Optional.of(LocalTime.parse(value.strip()));
    }

    /** Fuseau IANA reconnu, ou vide. */
    public static Optional<ZoneId> parseZone(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ZoneId.of(value.strip()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Vrai si ce jour porte un créneau pour ce rythme. */
    public static boolean matches(LocalDate date, RoutineCadence cadence, Integer dayOfWeek) {
        DayOfWeek day = date.getDayOfWeek();
        return switch (cadence) {
            case DAILY -> true;
            case WEEKDAYS -> day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY;
            case WEEKLY -> dayOfWeek != null && day.getValue() == dayOfWeek;
        };
    }

    /** L'instant d'un créneau (heure d'été comprise : une heure qui n'existe pas glisse d'une heure). */
    public static OffsetDateTime slotInstant(LocalDate date, LocalTime time, ZoneId zone) {
        return ZonedDateTime.of(date, time, zone).toOffsetDateTime();
    }

    /**
     * Le <b>créneau dû</b> : le dernier créneau passé (ou qui tombe maintenant), ou vide si aucun dans
     * la fenêtre — ce qui n'arrive que pour un rythme hebdomadaire sans jour.
     */
    public static Optional<OffsetDateTime> dueSlot(OffsetDateTime now, RoutineCadence cadence, Integer dayOfWeek,
            LocalTime time, ZoneId zone) {
        LocalDate today = now.atZoneSameInstant(zone).toLocalDate();
        for (int back = 0; back <= SEARCH_DAYS; back++) {
            LocalDate date = today.minusDays(back);
            if (!matches(date, cadence, dayOfWeek)) {
                continue;
            }
            OffsetDateTime slot = slotInstant(date, time, zone);
            if (!slot.isAfter(now)) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    /** Le prochain créneau <b>strictement</b> après maintenant, ou vide. */
    public static Optional<OffsetDateTime> nextSlot(OffsetDateTime now, RoutineCadence cadence, Integer dayOfWeek,
            LocalTime time, ZoneId zone) {
        LocalDate today = now.atZoneSameInstant(zone).toLocalDate();
        for (int ahead = 0; ahead <= SEARCH_DAYS; ahead++) {
            LocalDate date = today.plusDays(ahead);
            if (!matches(date, cadence, dayOfWeek)) {
                continue;
            }
            OffsetDateTime slot = slotInstant(date, time, zone);
            if (slot.isAfter(now)) {
                return Optional.of(slot);
            }
        }
        return Optional.empty();
    }

    /** Créneau dû d'une routine (fuseau et heure illisibles ⇒ vide : rien ne part). */
    public static Optional<OffsetDateTime> dueSlot(SubjectRoutine routine, OffsetDateTime now) {
        Optional<LocalTime> time = parseTime(routine.getRunTime());
        Optional<ZoneId> zone = parseZone(routine.getTimeZone());
        if (time.isEmpty() || zone.isEmpty() || routine.getCadence() == null) {
            return Optional.empty();
        }
        return dueSlot(now, routine.getCadence(), routine.getDayOfWeek(), time.get(), zone.get());
    }

    /** Prochain créneau d'une routine. */
    public static Optional<OffsetDateTime> nextSlot(SubjectRoutine routine, OffsetDateTime now) {
        Optional<LocalTime> time = parseTime(routine.getRunTime());
        Optional<ZoneId> zone = parseZone(routine.getTimeZone());
        if (time.isEmpty() || zone.isEmpty() || routine.getCadence() == null) {
            return Optional.empty();
        }
        return nextSlot(now, routine.getCadence(), routine.getDayOfWeek(), time.get(), zone.get());
    }
}
