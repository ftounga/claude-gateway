package fr.claudegateway.pages;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>L'embarquement des images d'une page à la livraison</b> (F-142 / SF-142-22).
 *
 * <h2>Le problème</h2>
 *
 * <p>Une page F-109 est servie sous {@link PageContentPolicy} : bac à sable <b>sans</b>
 * {@code allow-same-origin}, donc <b>origine opaque</b>. Dans {@code img-src data: blob: 'self'}, le
 * {@code 'self'} ne correspond alors à <b>aucune</b> origine, et une image en pièce jointe référencée en
 * relatif ({@code <img src="diagram.svg">}) est <b>bloquée inline</b>. {@code blob:} est inutilisable
 * ({@code connect-src 'none'} empêche de fetcher la pièce jointe pour en faire un blob). <b>Seul
 * {@code data:} passe</b> — voir {@link PageContentPolicy#allowsInlineImage(String)}.</p>
 *
 * <h2>Ce que fait ce composant</h2>
 *
 * <p>Au moment de <b>servir</b> une page (jamais au stockage : le HTML reste pristine, la pièce jointe
 * n'est rangée qu'<b>une</b> fois), on remplace la valeur {@code src} de chaque {@code <img>} qui pointe
 * vers une pièce jointe <b>image</b> de la version par un {@code data:<type>;base64,…} construit à partir
 * de ses octets. Le lien « ouvrir en grand » ({@code <a href="diagram.svg" target="_blank">}) reste
 * relatif — c'est une navigation top-level, autorisée par {@code allow-popups} et non soumise à
 * {@code img-src} — et continue de fonctionner.</p>
 *
 * <h2>Non-régression stricte</h2>
 *
 * <p>Une page sans {@code <img>} embarquable est rendue <b>octet pour octet identique</b> à ce qui est
 * stocké : {@link #inline(byte[], Function)} renvoie alors le tableau reçu, inchangé. L'opération est
 * idempotente : un {@code src} déjà en {@code data:} (ou une URL, ou un chemin) est laissé tel quel.</p>
 */
public final class PageImageInliner {

    /** Une balise image entière, pour n'agir que sur les {@code <img>} (jamais sur un {@code <a href>}). */
    private static final Pattern IMG_TAG = Pattern.compile("<img\\b[^>]*>", Pattern.CASE_INSENSITIVE);

    /** L'attribut {@code src} d'une balise, guillemets simples ou doubles. */
    private static final Pattern SRC_ATTR = Pattern.compile(
            "(\\bsrc\\s*=\\s*)(\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);

    private PageImageInliner() {
    }

    /**
     * Embarque les images d'une page servie ; renvoie le tableau reçu <b>inchangé</b> si rien n'est
     * embarqué (byte-identité garantie).
     *
     * @param html        le HTML servi (UTF-8)
     * @param attachments résolveur nom → octets de la pièce jointe, <b>déjà borné au propriétaire</b>
     */
    public static byte[] inline(byte[] html, Function<String, Optional<byte[]>> attachments) {
        if (html == null || html.length == 0) {
            return html;
        }
        String source = new String(html, StandardCharsets.UTF_8);
        String out = inline(source, attachments);
        return out.equals(source) ? html : out.getBytes(StandardCharsets.UTF_8);
    }

    /** La même transformation sur une chaîne (commodité pour les tests et l'appel direct). */
    public static String inline(String html, Function<String, Optional<byte[]>> attachments) {
        if (html == null || html.isEmpty() || attachments == null) {
            return html;
        }
        // Aucune balise réécrite ⇒ {@code out} reste null ⇒ la chaîne reçue est renvoyée telle quelle
        // (byte-identité garantie en amont par la surcharge sur byte[]).
        Matcher tags = IMG_TAG.matcher(html);
        StringBuilder out = null;
        int last = 0;
        while (tags.find()) {
            String tag = tags.group();
            String rewritten = rewriteTag(tag, attachments);
            if (!rewritten.equals(tag)) {
                if (out == null) {
                    out = new StringBuilder(html.length() + 1024);
                }
                out.append(html, last, tags.start()).append(rewritten);
                last = tags.end();
            }
        }
        if (out == null) {
            return html;
        }
        out.append(html, last, html.length());
        return out.toString();
    }

    /** Réécrit une balise {@code <img>} si son {@code src} désigne une pièce jointe image ; sinon la rend telle quelle. */
    private static String rewriteTag(String tag, Function<String, Optional<byte[]>> attachments) {
        Matcher m = SRC_ATTR.matcher(tag);
        if (!m.find()) {
            return tag;
        }
        String quoted = m.group(2);
        String value = m.group(3) != null ? m.group(3) : m.group(4);
        String name = value == null ? "" : value.strip();
        // Un nom plat de pièce jointe : ni schéma (data:, http:, blob:), ni dossier, ni "..". Tout le
        // reste — déjà en data:, une URL, un chemin — est laissé intact (idempotence).
        if (!PageAttachments.isValidName(name)) {
            return tag;
        }
        Optional<String> type = PageAttachments.contentType(name);
        if (type.isEmpty() || !isImage(type.get())) {
            return tag;
        }
        Optional<byte[]> bytes = attachments.apply(name);
        if (bytes.isEmpty() || bytes.get().length == 0) {
            return tag;
        }
        String dataUri = "data:" + mediaType(type.get()) + ";base64,"
                + Base64.getEncoder().encodeToString(bytes.get());
        char quote = quoted.charAt(0);
        String replacement = m.group(1) + quote + dataUri + quote;
        return tag.substring(0, m.start()) + replacement + tag.substring(m.end());
    }

    private static boolean isImage(String contentType) {
        return contentType.startsWith("image/");
    }

    /** Le type sans paramètre ({@code image/svg+xml}, {@code image/png}) — les images n'ont pas de charset. */
    private static String mediaType(String contentType) {
        int semi = contentType.indexOf(';');
        return (semi < 0 ? contentType : contentType.substring(0, semi)).strip();
    }
}
