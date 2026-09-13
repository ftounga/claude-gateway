package fr.claudegateway.radar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * <b>La relance due</b> (F-101 / SF-101-04). Aucune dépendance, aucun état.
 *
 * <p>Seul un engagement <i>« j'attends des autres »</i> ouvert appelle une relance : ce que je dois
 * faire, ou une mise en relation que je dois faire, ne se relance pas — c'est à moi d'agir. Une
 * échéance connue fait foi (relance le premier jour ouvré qui la suit) ; sans elle, on relance
 * {@value #DEFAULT_BUSINESS_DAYS} jours ouvrés après la preuve la plus récente. Samedi et dimanche ne
 * sont pas ouvrés ; les jours fériés ne sont pas connus.</p>
 */
public final class RadarFollowUp {

    /** Délai de relance sans échéance, en jours ouvrés (cadrage §12 bis). */
    public static final int DEFAULT_BUSINESS_DAYS = 3;

    private RadarFollowUp() {
    }

    /** Le jour de relance d'un engagement, ou {@code null} s'il n'en appelle pas. */
    public static LocalDate dueOn(RadarCommitmentDirection direction, RadarCommitmentStatus status,
            boolean disowned, LocalDate dueDate, OffsetDateTime lastEvidenceAt) {
        if (direction != RadarCommitmentDirection.OTHER_TO_ME || status != RadarCommitmentStatus.OPEN || disowned) {
            return null;
        }
        if (dueDate != null) {
            return addBusinessDays(dueDate, 1);
        }
        if (lastEvidenceAt == null) {
            return null;
        }
        return addBusinessDays(lastEvidenceAt.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate(),
                DEFAULT_BUSINESS_DAYS);
    }

    /** Ajoute des jours ouvrés (hors samedi et dimanche). */
    public static LocalDate addBusinessDays(LocalDate from, int days) {
        LocalDate day = from;
        int added = 0;
        while (added < days) {
            day = day.plusDays(1);
            if (day.getDayOfWeek() != DayOfWeek.SATURDAY && day.getDayOfWeek() != DayOfWeek.SUNDAY) {
                added++;
            }
        }
        return day;
    }
}
