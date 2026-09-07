package fr.claudegateway.runner;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.text.Normalizer;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Adapte le texte de la console au jeu de caractères du terminal (F-38 / SF-38-26).
 *
 * <p>Le runner écrit en français typographique : points de suspension, tiret cadratin, apostrophe
 * courbe. Sur une console Windows — <b>cp850</b> en France, cp437 aux États-Unis — aucun de ces
 * trois caractères n'a de correspondance, et {@code System.out} les remplace silencieusement par
 * {@code ?} :</p>
 *
 * <pre>
 * [10:02:14] INFO  Appairage aupr?s de https://.../runner/pair?
 * </pre>
 *
 * <p>Le piège de ce défaut est qu'il épargne les accents : {@code é}, {@code à} et les guillemets
 * français <b>existent</b> en cp850. Le texte a donc l'air correct en relecture, et seuls les points
 * de suspension et les tirets tombent.</p>
 *
 * <p><b>On adapte le texte, on ne reconfigure pas le terminal (D2)</b> : depuis Java, la page de
 * code d'une console Windows ne se change pas, et y écrire des octets UTF-8 produit du mojibake —
 * un défaut pire, puisqu'il atteindrait aussi les accents. Si le jeu du terminal sait écrire le
 * caractère, il passe <b>intact</b> ; sur un terminal UTF-8, rien ne change.</p>
 *
 * <p>Le repli n'écrit <b>jamais</b> de {@code ?} : c'est précisément le symptôme qu'on supprime. Un
 * caractère sans équivalent connu est décomposé (NFD) et dépouillé de ses diacritiques ; s'il reste
 * inencodable, il est retiré.</p>
 */
final class ConsoleEncoding {

    /**
     * Propriétés consultées, dans l'ordre, pour connaître le jeu de la sortie standard.
     * {@code stdout.encoding} est la propriété officielle depuis Java 19 ; {@code sun.stdout.encoding}
     * la couvre sur les JVM antérieures, et les deux suivantes servent de filet.
     */
    private static final List<String> CHARSET_PROPERTIES =
            List.of("stdout.encoding", "sun.stdout.encoding", "native.encoding", "file.encoding");

    private final CharsetEncoder encoder;

    private ConsoleEncoding(CharsetEncoder encoder) {
        this.encoder = encoder;
    }

    /** Adaptateur pour le jeu de caractères donné. */
    static ConsoleEncoding forCharset(Charset charset) {
        return new ConsoleEncoding(charset.newEncoder());
    }

    /**
     * Adaptateur déduit des propriétés système, sans jamais lever : une console est un confort, elle
     * ne fait pas échouer un runner. Un nom absent, vide ou inconnu de la JVM passe au suivant, et
     * {@link Charset#defaultCharset()} ferme la liste.
     *
     * @param properties lecture des propriétés système (injectée pour les tests)
     */
    static ConsoleEncoding forSystem(UnaryOperator<String> properties) {
        for (String key : CHARSET_PROPERTIES) {
            String name = properties.apply(key);
            if (name == null || name.trim().isEmpty()) {
                continue;
            }
            try {
                return forCharset(Charset.forName(name.trim()));
            } catch (IllegalCharsetNameException | UnsupportedCharsetException inconnu) {
                // Jeu que cette JVM ne connaît pas : on essaie la propriété suivante.
            }
        }
        return forCharset(Charset.defaultCharset());
    }

    /**
     * Le message tel qu'il peut être écrit sur ce terminal. Identique à l'entrée dès que le jeu sait
     * tout écrire — le cas de tous les terminaux UTF-8.
     *
     * <p><b>Synchronisée</b> : un {@link CharsetEncoder} porte un état interne et n'est pas
     * réentrant, alors que la console est écrite depuis le thread principal, le heartbeat et les
     * threads d'outils. Le coût est nul à l'échelle de quelques lignes par seconde.</p>
     */
    synchronized String render(String message) {
        if (message == null || encoder.canEncode(message)) {
            return message;
        }
        StringBuilder out = new StringBuilder(message.length() + 8);
        for (int i = 0; i < message.length(); i++) {
            char c = message.charAt(i);
            if (encoder.canEncode(c)) {
                out.append(c);
                continue;
            }
            String equivalent = asciiEquivalent(c);
            out.append(equivalent != null ? encodable(equivalent) : stripDiacritics(c));
        }
        return out.toString();
    }

    /**
     * Équivalents ASCII des caractères que les consoles OEM ne savent pas écrire. Ils ne sont
     * consultés que si l'encodeur du terminal a <b>refusé</b> le caractère d'origine : sur un cp850,
     * qui écrit les guillemets français, la ligne {@code «} n'est jamais atteinte.
     */
    private static String asciiEquivalent(char c) {
        switch (c) {
            case '…': return "...";  // … points de suspension
            case '—':                // — cadratin
            case '–':                // – demi-cadratin
            case '−': return "-";    // − signe moins
            case '’':                // ’ apostrophe courbe
            case '‘':                // ‘
            case 'ʼ': return "'";    // ʼ
            case '“':                // “
            case '”':                // ”
            case '„': return "\"";   // „
            case '«': return "<<";   // «
            case '»': return ">>";   // »
            case '€': return "EUR";  // €
            case 'œ': return "oe";   // œ
            case 'Œ': return "OE";   // Œ
            case 'æ': return "ae";   // æ
            case 'Æ': return "AE";   // Æ
            case '•': return "*";    // • puce
            case '→': return "->";   // →
            case '×': return "x";    // ×
            case ' ':                // espace insécable
            case ' ': return " ";    // espace fine insécable
            default: return null;
        }
    }

    /**
     * Le remplacement lui-même doit tenir dans le jeu : sur un terminal US-ASCII, {@code «} devient
     * {@code <<}. Un remplacement qui ne passerait pas non plus est abandonné plutôt que rendu en
     * {@code ?}.
     */
    private String encodable(String replacement) {
        return encoder.canEncode(replacement) ? replacement : "";
    }

    /**
     * Dernier recours : {@code é} devient {@code e} par décomposition Unicode. Ce qui résiste encore
     * (idéogramme dans un nom de dossier, émoji) est <b>retiré</b> plutôt que rendu en {@code ?}.
     */
    private String stripDiacritics(char c) {
        String decomposed = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
        StringBuilder kept = new StringBuilder(1);
        for (int i = 0; i < decomposed.length(); i++) {
            char part = decomposed.charAt(i);
            if (Character.getType(part) != Character.NON_SPACING_MARK && encoder.canEncode(part)) {
                kept.append(part);
            }
        }
        return kept.toString();
    }
}
