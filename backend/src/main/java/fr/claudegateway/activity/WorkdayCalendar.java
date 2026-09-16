package fr.claudegateway.activity;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Set;

/**
 * Le <b>compte des jours ouvrés</b> d'un mois (F-124 / SF-124-02) : lundi–vendredi, <b>hors jours
 * fériés France</b> ({@link FrenchHolidays}). Fonctions pures.
 *
 * <p>C'est le nombre de jours « supposés » d'un mois complet non déclaré. Le week-end et les fériés
 * n'y comptent pas — un mois de mai (1er mai, 8 mai, Ascension) rend donc moins que quatre semaines
 * pleines.</p>
 */
public final class WorkdayCalendar {

    private WorkdayCalendar() {
    }

    /** Nombre de jours ouvrés (lun–ven hors fériés) du mois donné. */
    public static int businessDaysInMonth(YearMonth month) {
        Set<LocalDate> holidays = FrenchHolidays.of(month.getYear());
        int count = 0;
        LocalDate day = month.atDay(1);
        LocalDate end = month.atEndOfMonth();
        while (!day.isAfter(end)) {
            if (isBusinessDay(day, holidays)) {
                count++;
            }
            day = day.plusDays(1);
        }
        return count;
    }

    private static boolean isBusinessDay(LocalDate day, Set<LocalDate> holidays) {
        DayOfWeek weekday = day.getDayOfWeek();
        if (weekday == DayOfWeek.SATURDAY || weekday == DayOfWeek.SUNDAY) {
            return false;
        }
        return !holidays.contains(day);
    }
}
