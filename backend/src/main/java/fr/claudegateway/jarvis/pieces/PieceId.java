package fr.claudegateway.jarvis.pieces;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Un identifiant de pièce, lu</b> (F-237 / SF-237-02) : {@code proof:<hex>}, {@code page:<uuid>@v3}… Jamais un
 * chemin : un identifiant mal formé ne se résout en rien (404).
 *
 * @param prefix  le type d'identifiant ({@code dep}, {@code proof}, {@code page}, {@code video}, {@code img},
 *                {@code slides}, {@code send}, {@code mail}, {@code req}, {@code need}, {@code diag})
 * @param id      l'identifiant de la ligne
 * @param version la version citée ({@code @v3}), {@code null} sinon
 */
public record PieceId(String prefix, UUID id, Integer version) {

    private static final Pattern FORM = Pattern.compile(
            "^(dep|proof|page|video|img|slides|send|mail|req|need|diag):([0-9a-fA-F-]{32,36})(?:@v(\\d{1,6}))?$");

    /** Lit un identifiant ; vide s'il est mal formé. */
    public static Optional<PieceId> parse(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher m = FORM.matcher(raw.strip());
        if (!m.matches()) {
            return Optional.empty();
        }
        String body = m.group(2);
        UUID id;
        try {
            id = body.length() == 32 ? UUID.fromString(body.replaceFirst(
                    "(\\p{XDigit}{8})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{4})(\\p{XDigit}{12})",
                    "$1-$2-$3-$4-$5")) : UUID.fromString(body);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        Integer version = m.group(3) == null ? null : Integer.valueOf(m.group(3));
        return Optional.of(new PieceId(m.group(1), id, version));
    }
}
