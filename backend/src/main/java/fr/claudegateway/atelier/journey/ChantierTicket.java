package fr.claudegateway.atelier.journey;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>La clé de ticket d'un chantier</b> (F-271 / SF-271-01, D3) — un chantier ouvert pour un ticket porte sa clé en tête
 * de titre ({@code LDIC-325 — créer le topic SNS}) : Jarvis et l'écran le retrouvent par elle, sans colonne de plus.
 */
public final class ChantierTicket {

    /** Une clé de ticket dans un texte : lettres, tiret, chiffres ; la casse est ignorée (transcription « ldic-325 »). */
    static final Pattern KEY = Pattern.compile("(?i)(?<![\\p{L}\\p{N}-])([A-Z][A-Z0-9]{1,9}-\\d{1,6})(?![\\p{L}\\p{N}])");

    private static final Pattern WHOLE = Pattern.compile("[A-Z][A-Z0-9]{1,9}-\\d{1,6}");

    /** Le séparateur entre la clé et la raison. */
    static final String SEPARATOR = " — ";

    private ChantierTicket() {
    }

    /** La clé donnée, normalisée en majuscules, ou {@code null} si elle n'en est pas une. */
    public static String normalized(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.strip().toUpperCase(Locale.ROOT);
        return WHOLE.matcher(key).matches() ? key : null;
    }

    /** Le titre d'un chantier pour ce ticket : {@code KEY — raison}, sans répéter la clé si la raison la porte déjà. */
    public static String title(String ticket, String reason) {
        String key = normalized(ticket);
        String text = reason == null ? "" : reason.strip();
        if (key == null) {
            return text;
        }
        if (text.isEmpty()) {
            return key;
        }
        if (text.toUpperCase(Locale.ROOT).startsWith(key)) {
            return text;
        }
        return key + SEPARATOR + text;
    }

    /** Vrai si le titre porte cette clé (mot entier). */
    public static boolean carries(String title, String key) {
        String k = normalized(key);
        return title != null && k != null && keysIn(title).contains(k);
    }

    /** Les clés citées dans un texte libre, dans l'ordre, en majuscules. */
    public static Set<String> keysIn(String text) {
        Set<String> keys = new LinkedHashSet<>();
        if (text == null) {
            return keys;
        }
        Matcher m = KEY.matcher(text);
        while (m.find()) {
            keys.add(m.group(1).toUpperCase(Locale.ROOT));
        }
        return keys;
    }
}
