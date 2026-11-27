package fr.claudegateway.atelier.gallery;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>La date lisible dans le nom d'une capture</b> (F-278 / SF-278-01) : {@code Capture d'écran 2026-11-27 093012.png},
 * {@code Screenshot_20261127_093012.png}, {@code IMG-20261127.jpg}… — le dernier recours quand ni l'agent ni le dépôt
 * ne donnent la date. Fonction pure ; une date invalide (mois 13) ne donne rien.
 */
public final class GalleryDates {

    private static final Pattern DATE = Pattern.compile(
            "(?<!\\d)(20\\d{2})[-_.]?(\\d{2})[-_.]?(\\d{2})(?:[ _T.\\-à]{0,3}(\\d{2})[h.:\\-]?(\\d{2})(?:[m.:\\-]?(\\d{2}))?)?(?!\\d)");

    private GalleryDates() {
    }

    /** La date du nom, à la minute (ou au jour), ou vide. */
    public static Optional<LocalDateTime> fromName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        Matcher m = DATE.matcher(name);
        while (m.find()) {
            try {
                LocalDate day = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)));
                if (m.group(4) == null) {
                    return Optional.of(day.atStartOfDay());
                }
                int hour = Integer.parseInt(m.group(4));
                int minute = Integer.parseInt(m.group(5));
                int second = m.group(6) == null ? 0 : Integer.parseInt(m.group(6));
                if (hour > 23 || minute > 59 || second > 59) {
                    return Optional.of(day.atStartOfDay());
                }
                return Optional.of(day.atTime(hour, minute, second));
            } catch (DateTimeException | NumberFormatException e) {
                // date impossible : on cherche plus loin
            }
        }
        return Optional.empty();
    }
}
