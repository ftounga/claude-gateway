package fr.claudegateway.atelier.deposit;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Choisit le nom <b>final</b> d'un fichier déposé (F-201 / SF-201-01) — fonction pure.
 *
 * <p>Le navigateur nomme toute capture collée {@code image.png} : sans ce choix, chaque collage
 * écrasait le précédent et l'agent lisait deux fois la dernière image. Le serveur est la source de
 * vérité : un nom générique devient {@code capture-AAAAMMJJ-HHMMSS.<ext>}, et un nom déjà pris
 * reçoit un suffixe {@code -2}, {@code -3}… avant l'extension. Jamais d'écrasement.</p>
 */
public final class DepositNameAllocator {

    /** Noms que le navigateur ou le presse-papiers donnent à une capture : {@code image.png}, {@code image (2).png}, {@code blob}… */
    private static final Pattern GENERIC = Pattern.compile(
            "^(image|blob|pasted image|screenshot)( ?\\(\\d+\\))?(\\.[a-z0-9]{1,10})?$",
            Pattern.CASE_INSENSITIVE);

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private DepositNameAllocator() {
    }

    /** Vrai pour un nom de capture générique, qui ne dit rien de son contenu. */
    public static boolean isGeneric(String name) {
        return name != null && GENERIC.matcher(name.trim()).matches();
    }

    /**
     * @param sanitized nom déjà assaini ({@link DepositFileName#sanitize})
     * @param taken noms déjà pris dans ce dossier de dépôt, <b>en minuscules</b>
     * @param now instant du dépôt, dans le fuseau d'affichage
     * @return un nom absent de {@code taken} (sans casse), de 255 caractères au plus
     */
    public static String allocate(String sanitized, Set<String> taken, LocalDateTime now) {
        String base;
        String ext;
        if (isGeneric(sanitized)) {
            String extension = extensionOf(sanitized);
            base = "capture-" + STAMP.format(now);
            ext = extension.isEmpty() ? ".png" : extension.toLowerCase(Locale.ROOT);
        } else {
            ext = extensionOf(sanitized);
            base = sanitized.substring(0, sanitized.length() - ext.length());
        }
        String candidate = fit(base, ext, "");
        for (int n = 2; taken.contains(candidate.toLowerCase(Locale.ROOT)); n++) {
            candidate = fit(base, ext, "-" + n);
        }
        return candidate;
    }

    /** Extension avec son point ({@code .png}), ou vide ; un nom commençant par un point n'en a pas. */
    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        if (dot <= 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot);
    }

    /** Compose {@code base + suffixe + ext} en tronquant la base pour tenir en 255 caractères. */
    private static String fit(String base, String ext, String suffix) {
        int room = DepositFileName.MAX_NAME_LENGTH - ext.length() - suffix.length();
        String trimmed = base.length() > room ? base.substring(0, Math.max(1, room)) : base;
        return trimmed + suffix + ext;
    }
}
