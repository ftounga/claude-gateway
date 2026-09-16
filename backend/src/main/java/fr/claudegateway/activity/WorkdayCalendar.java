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

    /**
     * Nombre de jours ouvrés (lun–ven hors fériés France) dans l'intervalle {@code [from, to]},
     * <b>bornes incluses</b> (F-124 / SF-124-04). Rend {@code 0} si l'intervalle est vide
     * ({@code from} après {@code to}) — c'est le compte, jamais une exception, que la validation
     * traduira ensuite en refus « 0 jour ».
     *
     * <p>Sert à convertir une <b>plage de dates</b> décrite en langage naturel (« du 10 à la fin du
     * mois ») en jours ouvrés, <b>côté serveur</b> et de façon déterministe : le modèle décrit la
     * période, la Gateway compte.</p>
     */
    public static int businessDaysBetween(LocalDate from, LocalDate to) {
        if (from == null || to == null || from.isAfter(to)) {
            return 0;
        }
        Set<LocalDate> holidays = FrenchHolidays.of(from.getYear());
        int year = from.getYear();
        int count = 0;
        LocalDate day = from;
        while (!day.isAfter(to)) {
            if (day.getYear() != year) {         // l'intervalle change d'année : recharge les fériés
                year = day.getYear();
                holidays = FrenchHolidays.of(year);
            }
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
