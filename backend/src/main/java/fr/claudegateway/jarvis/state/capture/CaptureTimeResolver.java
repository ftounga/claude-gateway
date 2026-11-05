package fr.claudegateway.jarvis.state.capture;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>La date d'un message capturé, fixée par la passerelle</b> (F-247 / SF-247-12, D13) : l'horodatage visible
 * (« Hier 14:32 », « lundi 10:05 », « 04/11 14:32 », « 4 nov. », « 2:32 PM »…) est résolu de façon
 * <b>déterministe</b> à partir de l'heure du dépôt, au fuseau du PO. Sans date lisible, c'est l'heure du dépôt,
 * marquée « vers » ({@code approx}). Le modèle ne date jamais rien.
 */
public final class CaptureTimeResolver {

    public static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    /** Une date résolue : {@code approx} = « vers » (heure du dépôt, ou jour sans heure). */
    public record Resolved(OffsetDateTime at, boolean approx) {
    }

    private static final Pattern TIME = Pattern.compile("(\\d{1,2})\\s*[:h]\\s*(\\d{2})(?:\\s*([ap])\\.?\\s*m\\.?)?",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_AMPM = Pattern.compile("\\b(\\d{1,2})\\s*([ap])\\.?\\s*m\\b",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ISO = Pattern.compile("\\b(\\d{4})-(\\d{2})-(\\d{2})\\b");
    private static final Pattern NUMERIC = Pattern.compile("\\b(\\d{1,2})/(\\d{1,2})(?:/(\\d{2,4}))?\\b");
    private static final Pattern WORDS = Pattern.compile("\\b(\\d{1,2})(?:er)?\\s+([a-z]{3,9})\\.?(?:\\s+(\\d{4}))?");

    private static final Map<String, Integer> MONTHS = Map.ofEntries(Map.entry("janv", 1), Map.entry("jan", 1),
            Map.entry("fevr", 2), Map.entry("fev", 2), Map.entry("feb", 2), Map.entry("mars", 3), Map.entry("mar", 3),
            Map.entry("avr", 4), Map.entry("apr", 4), Map.entry("mai", 5), Map.entry("may", 5), Map.entry("juin", 6),
            Map.entry("jun", 6), Map.entry("juil", 7), Map.entry("jul", 7), Map.entry("aout", 8), Map.entry("aug", 8),
            Map.entry("sept", 9), Map.entry("sep", 9), Map.entry("oct", 10), Map.entry("nov", 11), Map.entry("dec", 12));

    private static final Map<String, DayOfWeek> DAYS = Map.ofEntries(Map.entry("lun", DayOfWeek.MONDAY),
            Map.entry("mon", DayOfWeek.MONDAY), Map.entry("mar", DayOfWeek.TUESDAY), Map.entry("tue", DayOfWeek.TUESDAY),
            Map.entry("mer", DayOfWeek.WEDNESDAY), Map.entry("wed", DayOfWeek.WEDNESDAY),
            Map.entry("jeu", DayOfWeek.THURSDAY), Map.entry("thu", DayOfWeek.THURSDAY),
            Map.entry("ven", DayOfWeek.FRIDAY), Map.entry("fri", DayOfWeek.FRIDAY), Map.entry("sam", DayOfWeek.SATURDAY),
            Map.entry("sat", DayOfWeek.SATURDAY), Map.entry("dim", DayOfWeek.SUNDAY), Map.entry("sun", DayOfWeek.SUNDAY));

    private CaptureTimeResolver() {
    }

    /** Résout un horodatage visible au regard de l'heure du dépôt. */
    public static Resolved resolve(String stamp, OffsetDateTime depositedAt) {
        ZonedDateTime ref = depositedAt.atZoneSameInstant(ZONE);
        String s = fold(stamp);
        if (s.isEmpty()) {
            return new Resolved(depositedAt, true);
        }
        LocalTime time = time(s);
        LocalDate date = date(s, ref.toLocalDate());
        if (date == null && time == null) {
            return new Resolved(depositedAt, true);
        }
        if (date == null) {
            date = ref.toLocalDate();
            // Une heure seule plus tard que le dépôt est celle de la veille.
            if (time.isAfter(ref.toLocalTime().plusMinutes(5)) && !s.contains("aujourd") && !s.contains("today")) {
                date = date.minusDays(1);
            }
        }
        if (date.isAfter(ref.toLocalDate())) {
            return new Resolved(depositedAt, true);
        }
        boolean approx = time == null;
        LocalTime t = time == null ? LocalTime.NOON : time;
        OffsetDateTime at = date.atTime(t).atZone(ZONE).toOffsetDateTime();
        if (at.isAfter(depositedAt.plusMinutes(5))) {
            return new Resolved(depositedAt, true);
        }
        return new Resolved(at, approx);
    }

    static LocalTime time(String s) {
        Matcher m = TIME.matcher(s);
        if (m.find()) {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            h = ampm(h, m.group(3));
            return h < 0 || h > 23 || min > 59 ? null : LocalTime.of(h, min);
        }
        Matcher a = TIME_AMPM.matcher(s);
        if (a.find()) {
            int h = ampm(Integer.parseInt(a.group(1)), a.group(2));
            return h < 0 || h > 23 ? null : LocalTime.of(h, 0);
        }
        return null;
    }

    private static int ampm(int h, String marker) {
        if (marker == null) {
            return h;
        }
        if (h < 1 || h > 12) {
            return -1;
        }
        boolean pm = marker.equalsIgnoreCase("p");
        return pm ? (h % 12) + 12 : h % 12;
    }

    static LocalDate date(String s, LocalDate today) {
        if (s.contains("aujourd") || s.contains("today")) {
            return today;
        }
        if (s.contains("avant-hier") || s.contains("avant hier")) {
            return today.minusDays(2);
        }
        if (s.contains("hier") || s.contains("yesterday")) {
            return today.minusDays(1);
        }
        Matcher iso = ISO.matcher(s);
        if (iso.find()) {
            return safe(Integer.parseInt(iso.group(1)), Integer.parseInt(iso.group(2)), Integer.parseInt(iso.group(3)));
        }
        Matcher num = NUMERIC.matcher(s);
        if (num.find()) {
            int day = Integer.parseInt(num.group(1));
            int month = Integer.parseInt(num.group(2));
            Integer year = num.group(3) == null ? null : Integer.parseInt(num.group(3));
            return withYear(day, month, year, today);
        }
        Matcher w = WORDS.matcher(s);
        while (w.find()) {
            Integer month = month(w.group(2));
            if (month != null) {
                Integer year = w.group(3) == null ? null : Integer.parseInt(w.group(3));
                return withYear(Integer.parseInt(w.group(1)), month, year, today);
            }
        }
        for (String token : s.split("[^a-z]+")) {
            if (token.length() >= 3) {
                DayOfWeek d = DAYS.get(token.substring(0, 3));
                if (d != null && isDayWord(token)) {
                    LocalDate x = today.minusDays(1);
                    while (x.getDayOfWeek() != d) {
                        x = x.minusDays(1);
                    }
                    return x;
                }
            }
        }
        return null;
    }

    /** Un mot de jour (« lundi », « lun », « monday », « mon »), pas un mot qui commence pareil (« mars », « samedi »). */
    private static boolean isDayWord(String token) {
        return token.length() == 3 || FULL_DAYS.contains(token);
    }

    private static final java.util.Set<String> FULL_DAYS = java.util.Set.of("lundi", "mardi", "mercredi", "jeudi",
            "vendredi", "samedi", "dimanche", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday",
            "sunday");

    private static Integer month(String word) {
        String w = word.length() > 5 ? word.substring(0, 5) : word;
        for (int n = Math.min(w.length(), 5); n >= 3; n--) {
            Integer m = MONTHS.get(w.substring(0, n));
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    private static LocalDate withYear(int day, int month, Integer year, LocalDate today) {
        if (year != null) {
            int y = year < 100 ? 2000 + year : year;
            return safe(y, month, day);
        }
        LocalDate d = safe(today.getYear(), month, day);
        return d != null && d.isAfter(today) ? safe(today.getYear() - 1, month, day) : d;
    }

    private static LocalDate safe(int y, int m, int d) {
        try {
            return LocalDate.of(y, m, d);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String fold(String raw) {
        if (raw == null) {
            return "";
        }
        String n = Normalizer.normalize(raw.strip().toLowerCase(Locale.ROOT), Normalizer.Form.NFD);
        return n.replaceAll("\\p{M}+", "").replace('’', '\'');
    }
}
