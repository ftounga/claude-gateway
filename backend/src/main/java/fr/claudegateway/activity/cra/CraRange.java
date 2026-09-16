package fr.claudegateway.activity.cra;

import java.time.LocalDate;
import java.time.YearMonth;

/**
 * Une <b>plage de dates</b> extraite d'un message de CRA par le modèle (F-124 / SF-124-04), avant
 * conversion en jours ouvrés par la Gateway.
 *
 * <p>Le modèle <b>décrit</b> la période, il ne <b>compte</b> jamais les jours (source d'erreurs) :
 * la conversion plage → jours ouvrés est faite <b>côté serveur</b> par
 * {@link fr.claudegateway.activity.WorkdayCalendar}. Une plage peut être exprimée de trois façons,
 * toutes résolues <b>dans le mois visé</b> de la ligne :</p>
 * <ul>
 *   <li>un <b>preset</b> — {@code FULL_MONTH} (tout le mois), {@code FIRST_HALF} (1ʳᵉ quinzaine,
 *       1→15), {@code SECOND_HALF} (2ᵉ quinzaine, 16→fin) ;</li>
 *   <li>des <b>jours du mois</b> — {@code fromDay} (défaut 1) et {@code toDay} (défaut : fin du
 *       mois) ; « du 10 à la fin du mois » = {@code fromDay=10} ;</li>
 *   <li>des <b>dates ISO</b> — {@code from}/{@code to} ({@code YYYY-MM-DD}), résolues dans le mois
 *       de {@code from}.</li>
 * </ul>
 *
 * @param preset  tournure normalisée ({@code FULL_MONTH}/{@code FIRST_HALF}/{@code SECOND_HALF}) ou {@code null}
 * @param fromDay jour de début dans le mois (1..fin), ou {@code null} → 1er jour
 * @param toDay   jour de fin dans le mois (1..fin), ou {@code null} → dernier jour
 * @param from    date ISO de début, ou {@code null}
 * @param to      date ISO de fin, ou {@code null}
 */
public record CraRange(String preset, Integer fromDay, Integer toDay, LocalDate from, LocalDate to) {

    /** {@code FULL_MONTH} — tout le mois. */
    public static final String FULL_MONTH = "FULL_MONTH";
    /** {@code FIRST_HALF} — 1ʳᵉ quinzaine (1→15). */
    public static final String FIRST_HALF = "FIRST_HALF";
    /** {@code SECOND_HALF} — 2ᵉ quinzaine (16→fin du mois). */
    public static final String SECOND_HALF = "SECOND_HALF";

    /** Vrai si la plage ne porte aucune information exploitable (tout est {@code null}). */
    public boolean isEmpty() {
        return preset == null && fromDay == null && toDay == null && from == null && to == null;
    }

    /**
     * Le mois visé par la plage : celui de {@code from} si des dates ISO sont données, sinon le mois
     * de la ligne ({@code lineMonth}). Une plage est toujours résolue <b>dans un seul mois</b>.
     */
    public YearMonth targetMonth(YearMonth lineMonth) {
        return from != null ? YearMonth.from(from) : lineMonth;
    }
}
