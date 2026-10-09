package fr.claudegateway.pages.pdf;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Ce qu'une page va chercher dehors</b> (F-184 / SF-184-02) — la partie pure de l'assemblage du lot.
 *
 * <p>Le service de rendu imprime hors ligne : tout ce que la page charge doit être <b>dans le lot</b>.
 * On relève ici les références <b>statiques</b> du HTML ({@code <script src>}, {@code <link href>},
 * {@code @import}) et, dans une feuille Google Fonts, les fichiers de police. On ne garde que la
 * <b>liste fermée</b> d'hôtes que le guide de conception autorise déjà (F-109) — jamais une autre
 * adresse, jamais en clair.</p>
 */
public final class PageLotScanner {

    /** Les hôtes dont la page peut charger scripts et polices — ceux de la CSP des pages, ni plus ni moins. */
    public static final Set<String> ALLOWED_HOSTS = Set.of(
            "cdnjs.cloudflare.com", "cdn.jsdelivr.net", "fonts.googleapis.com", "fonts.gstatic.com");

    private static final Pattern SCRIPT_SRC = Pattern.compile(
            "<script\\b[^>]*?\\bsrc\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_HREF = Pattern.compile(
            "<link\\b[^>]*?\\bhref\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_IMPORT = Pattern.compile(
            "@import\\s+(?:url\\(\\s*)?[\"']?([^\"')\\s;]+)", Pattern.CASE_INSENSITIVE);
    /** Un bloc {@code @font-face} précédé de son commentaire de sous-ensemble ({@code /* latin *\/}). */
    private static final Pattern FONT_FACE = Pattern.compile(
            "/\\*\\s*([a-z0-9-]+)\\s*\\*/\\s*@font-face\\s*\\{([^}]*)}", Pattern.CASE_INSENSITIVE);
    private static final Pattern CSS_URL = Pattern.compile(
            "url\\(\\s*[\"']?([^\"')\\s]+)[\"']?\\s*\\)", Pattern.CASE_INSENSITIVE);
    /** Les sous-ensembles gardés : l'alphabet latin couvre le français ; le reste gonflerait le lot. */
    private static final Set<String> FONT_SUBSETS = Set.of("latin", "latin-ext");

    private PageLotScanner() {
    }

    /** Les adresses externes autorisées que le HTML référence, dans l'ordre, sans doublon. */
    public static List<URI> externalReferences(String html) {
        Set<URI> out = new LinkedHashSet<>();
        if (html == null || html.isEmpty()) {
            return List.of();
        }
        for (Pattern pattern : List.of(SCRIPT_SRC, LINK_HREF)) {
            Matcher m = pattern.matcher(html);
            while (m.find()) {
                allowed(m.group(1) != null ? m.group(1) : m.group(2)).ifPresent(out::add);
            }
        }
        Matcher imports = CSS_IMPORT.matcher(html);
        while (imports.find()) {
            allowed(imports.group(1)).ifPresent(out::add);
        }
        return List.copyOf(out);
    }

    /**
     * Les fichiers de police d'une feuille Google Fonts, limités aux sous-ensembles {@code latin} et
     * {@code latin-ext}, sur {@code fonts.gstatic.com} seulement.
     */
    public static List<URI> fontFiles(String css) {
        Set<URI> out = new LinkedHashSet<>();
        if (css == null || css.isEmpty()) {
            return List.of();
        }
        Matcher face = FONT_FACE.matcher(css);
        while (face.find()) {
            if (!FONT_SUBSETS.contains(face.group(1).toLowerCase(Locale.ROOT))) {
                continue;
            }
            Matcher url = CSS_URL.matcher(face.group(2));
            while (url.find()) {
                allowed(url.group(1))
                        .filter(uri -> "fonts.gstatic.com".equals(uri.getHost()))
                        .ifPresent(out::add);
            }
        }
        return List.copyOf(out);
    }

    /** Vrai si l'adresse est une feuille Google Fonts (à déplier en fichiers de police). */
    public static boolean isFontStylesheet(URI uri) {
        return "fonts.googleapis.com".equals(uri.getHost());
    }

    /**
     * L'adresse, si elle est absolue, en HTTPS, sur un hôte autorisé, sans identifiants ni port exotique.
     * Les entités HTML courantes ({@code &amp;}) sont décodées : une URL Google Fonts en porte.
     */
    static Optional<URI> allowed(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        String value = raw.strip().replace("&amp;", "&");
        if (value.startsWith("//")) {
            value = "https:" + value;
        }
        // Sans fragment : le moteur compare les adresses sans fragment.
        int hash = value.indexOf('#');
        if (hash >= 0) {
            value = value.substring(0, hash);
        }
        URI uri;
        try {
            uri = new URI(value);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)
                || !ALLOWED_HOSTS.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            return Optional.empty();
        }
        return Optional.of(uri);
    }
}
