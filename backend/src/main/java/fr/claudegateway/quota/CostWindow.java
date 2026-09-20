package fr.claudegateway.quota;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.time.temporal.WeekFields;

/**
 * Fenêtre d'observation de la <b>dépense réelle</b> (F-133 / SF-133-03) : une semaine ou un mois.
 *
 * <p><b>Pourquoi ce n'est pas {@link UsageWindow}.</b> Celle-ci explique dans sa propre javadoc que
 * le mois est son grain <i>parce que les compteurs de période (F-10) ont le mois pour grain</i> —
 * offrir plus fin « laisserait croire à une précision qui n'existe pas ». C'est exact pour les
 * compteurs, et faux pour le journal par tour, qui est <b>horodaté à la seconde</b>. Étendre
 * {@code UsageWindow} mêlerait deux contraintes opposées dans un même objet et ferait porter au
 * rapport d'usage (F-16) et à la consommation par client (F-61) le risque d'une régression pour un
 * besoin qui ne les concerne pas.</p>
 *
 * <p><b>La semaine est ISO, du lundi 00:00 UTC.</b> Tout le reste des fenêtres de la plateforme est
 * en UTC ; choisir un autre fuseau ici ferait dépendre une frontière de budget de l'heure d'été.</p>
 *
 * @param start premier instant observé (inclus)
 * @param end   premier instant <b>exclu</b>
 */
public record CostWindow(OffsetDateTime start, OffsetDateTime end) {

    /** Plafond dur : au-delà, une lecture balaierait tout l'historique sur un paramètre distrait. */
    public static final int MAX_WEEKS = 53;

    public CostWindow {
        if (start == null || end == null) {
            throw new InvalidUsageWindowException("La période demandée est incomplète.");
        }
        if (!start.isBefore(end)) {
            throw new InvalidUsageWindowException("La date de début doit précéder la date de fin.");
        }
        if (ChronoUnit.WEEKS.between(start, end) > MAX_WEEKS) {
            throw new InvalidUsageWindowException(
                    "La période demandée dépasse " + MAX_WEEKS + " semaines.");
        }
    }

    /** La semaine ISO qui contient {@code day} : du lundi 00:00 UTC au lundi suivant, exclu. */
    public static CostWindow week(LocalDate day) {
        LocalDate monday = day.with(WeekFields.ISO.dayOfWeek(), 1L);
        return new CostWindow(atUtc(monday), atUtc(monday.plusWeeks(1)));
    }

    /** La semaine ISO courante. */
    public static CostWindow currentWeek(Clock clock) {
        return week(LocalDate.now(clock.withZone(ZoneOffset.UTC)));
    }

    /** Le mois calendaire qui contient {@code day}. */
    public static CostWindow month(LocalDate day) {
        LocalDate first = day.withDayOfMonth(1);
        return new CostWindow(atUtc(first), atUtc(first.plusMonths(1)));
    }

    /** Le mois calendaire courant. */
    public static CostWindow currentMonth(Clock clock) {
        return month(LocalDate.now(clock.withZone(ZoneOffset.UTC)));
    }

    /** Fenêtre libre entre deux jours, bornes en jours pleins UTC ({@code to} exclu). */
    public static CostWindow between(LocalDate from, LocalDate to) {
        if (from == null || to == null) {
            throw new InvalidUsageWindowException("La période demandée est incomplète.");
        }
        return new CostWindow(atUtc(from), atUtc(to));
    }

    /** Premier jour observé, pour l'affichage. */
    public LocalDate firstDay() {
        return start.toLocalDate();
    }

    /** Dernier jour observé <b>inclus</b>, pour l'affichage : la borne haute est exclusive. */
    public LocalDate lastDay() {
        return end.toLocalDate().minusDays(1);
    }

    private static OffsetDateTime atUtc(LocalDate day) {
        return day.atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
