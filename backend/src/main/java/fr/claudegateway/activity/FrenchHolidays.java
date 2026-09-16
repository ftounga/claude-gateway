package fr.claudegateway.activity;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;

/**
 * Les <b>jours fériés en France</b> (F-124 / SF-124-02), <b>calculés</b> — aucun service externe.
 *
 * <p>Sept fériés fixes (1er janvier, 1er mai, 8 mai, 14 juillet, 15 août, 1er novembre, 11 novembre,
 * 25 décembre) et trois fériés <b>mobiles</b> adossés à Pâques : le <b>lundi de Pâques</b>
 * (Pâques + 1), l'<b>Ascension</b> (Pâques + 39) et le <b>lundi de Pentecôte</b> (Pâques + 50).
 * Pâques est calculé par l'algorithme de Butcher/Meeus (grégorien).</p>
 *
 * <p>Fonctions pures, sans état ni dépendance : « conforme quelle que soit l'année » se prouve par des
 * tests de fonction.</p>
 */
public final class FrenchHolidays {

    private FrenchHolidays() {
    }

    /** Les jours fériés (métropole) de l'année donnée. */
    public static Set<LocalDate> of(int year) {
        Set<LocalDate> holidays = new HashSet<>();
        // Fériés fixes.
        holidays.add(LocalDate.of(year, 1, 1));    // Jour de l'an
        holidays.add(LocalDate.of(year, 5, 1));    // Fête du travail
        holidays.add(LocalDate.of(year, 5, 8));    // Victoire 1945
        holidays.add(LocalDate.of(year, 7, 14));   // Fête nationale
        holidays.add(LocalDate.of(year, 8, 15));   // Assomption
        holidays.add(LocalDate.of(year, 11, 1));   // Toussaint
        holidays.add(LocalDate.of(year, 11, 11));  // Armistice 1918
        holidays.add(LocalDate.of(year, 12, 25));  // Noël
        // Fériés mobiles adossés à Pâques.
        LocalDate easter = easterSunday(year);
        holidays.add(easter.plusDays(1));   // Lundi de Pâques
        holidays.add(easter.plusDays(39));  // Ascension (jeudi)
        holidays.add(easter.plusDays(50));  // Lundi de Pentecôte
        return holidays;
    }

    /** Vrai si la date est un jour férié en France. */
    public static boolean isHoliday(LocalDate date) {
        return of(date.getYear()).contains(date);
    }

    /**
     * Dimanche de Pâques (grégorien), par l'algorithme « Anonymous Gregorian » (Butcher/Meeus).
     * Déterministe et sans table : c'est ce qui rend les fériés mobiles testables en CI.
     */
    static LocalDate easterSunday(int year) {
        int a = year % 19;
        int b = year / 100;
        int c = year % 100;
        int d = b / 4;
        int e = b % 4;
        int f = (b + 8) / 25;
        int g = (b - f + 1) / 3;
        int h = (19 * a + b - d - g + 15) % 30;
        int i = c / 4;
        int k = c % 4;
        int l = (32 + 2 * e + 2 * i - h - k) % 7;
        int m = (a + 11 * h + 22 * l) / 451;
        int month = (h + l - 7 * m + 114) / 31;
        int day = ((h + l - 7 * m + 114) % 31) + 1;
        return LocalDate.of(year, month, day);
    }
}
