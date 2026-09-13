package fr.claudegateway.radar;

import java.text.Normalizer;
import java.util.Locale;

/**
 * Écriture Markdown de l'export du Radar (F-99 / SF-99-05). Sans état, testable sans base.
 *
 * <p>Tout texte venu d'une source est <b>échappé</b> : une citation qui contient {@code **} ou
 * {@code [lien](…)} s'affiche telle quelle, elle ne s'interprète pas.</p>
 */
public final class RadarMarkdown {

    private static final String SPECIAL = "\\`*_[]<>#|!";
    static final int MAX_FILE_SLUG = 60;

    private RadarMarkdown() {
    }

    /** Texte d'une source, sur une ligne, échappé. */
    public static String text(String value) {
        if (value == null) {
            return "";
        }
        String oneLine = value.replaceAll("[\\r\\n]+", " ").trim();
        StringBuilder out = new StringBuilder(oneLine.length() + 8);
        for (char c : oneLine.toCharArray()) {
            if (SPECIAL.indexOf(c) >= 0) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Un lien profond, seulement s'il est http(s) ; vide sinon. */
    public static String link(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return "";
        }
        String safe = url.trim().replace(" ", "%20").replace("(", "%28").replace(")", "%29")
                .replace("<", "%3C").replace(">", "%3E");
        return "[lien](" + safe + ")";
    }

    /** Nom de fichier : {@code radar-<poste>-<date>.md}, en minuscules ASCII. */
    public static String fileName(String hostName, java.time.LocalDate date) {
        String slug = Normalizer.normalize(hostName == null ? "" : hostName, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.length() > MAX_FILE_SLUG) {
            slug = slug.substring(0, MAX_FILE_SLUG).replaceAll("-+$", "");
        }
        if (slug.isEmpty()) {
            slug = "poste";
        }
        return "radar-" + slug + "-" + date + ".md";
    }
}
