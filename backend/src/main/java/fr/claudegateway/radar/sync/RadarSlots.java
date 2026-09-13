package fr.claudegateway.radar.sync;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;

/**
 * <b>Les créneaux du soir</b> (F-100 / SF-100-02) : quand tombe la synchro d'un poste, dans <b>son</b>
 * fuseau. Calcul pur, sans horloge ni base.
 */
public final class RadarSlots {

    private RadarSlots() {
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
        } catch (DateTimeParseException | java.time.zone.ZoneRulesException e) {
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** La date locale du dernier créneau passé (ou de celui qui tombe maintenant). */
    public static LocalDate dueSlotDate(OffsetDateTime now, LocalTime time, ZoneId zone) {
        ZonedDateTime local = now.atZoneSameInstant(zone);
        return local.toLocalTime().isBefore(time) ? local.toLocalDate().minusDays(1) : local.toLocalDate();
    }

    /** L'instant d'un créneau (heure d'été comprise : une heure qui n'existe pas glisse d'une heure). */
    public static OffsetDateTime slotInstant(LocalDate date, LocalTime time, ZoneId zone) {
        return ZonedDateTime.of(date, time, zone).toOffsetDateTime();
    }

    /** Le prochain créneau strictement après maintenant. */
    public static OffsetDateTime nextSlot(OffsetDateTime now, LocalTime time, ZoneId zone) {
        return slotInstant(dueSlotDate(now, time, zone).plusDays(1), time, zone);
    }
}
