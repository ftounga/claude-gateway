package fr.claudegateway.jarvis;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * <b>Le détail des scènes d'une journée</b> (F-234 / SF-234-11, D16) — « S3=12;S5=4;GENERIQUE=2 ». Seuls des
 * identifiants de scène (lettres majuscules et chiffres, 12 au plus) et des nombres : aucun contenu ne peut y entrer.
 * Pur et déterministe.
 */
public final class SceneCounts {

    /** La scène générique. */
    public static final String GENERIC = "GENERIQUE";
    /** Un identifiant de scène admis. */
    public static final Pattern SCENE_ID = Pattern.compile("^[A-Z][A-Za-z0-9]{0,11}$");
    /** Au plus autant de scènes distinctes gardées (le registre en compte une vingtaine). */
    static final int MAX_KEYS = 40;
    /** Au plus autant de scènes par déclaration (un tour). */
    public static final int MAX_PER_REPORT = 500;

    private SceneCounts() {
    }

    /** Lit le détail ; une entrée illisible est ignorée. */
    public static Map<String, Integer> parse(String raw) {
        Map<String, Integer> out = new TreeMap<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq).strip();
            try {
                int n = Integer.parseInt(part.substring(eq + 1).strip());
                if (SCENE_ID.matcher(key).matches() && n >= 0) {
                    out.merge(key, n, Integer::sum);
                }
            } catch (NumberFormatException e) {
                // entrée illisible : ignorée
            }
        }
        return out;
    }

    /** Écrit le détail, trié, borné à {@link #MAX_KEYS} scènes. */
    public static String format(Map<String, Integer> counts) {
        StringBuilder out = new StringBuilder();
        int kept = 0;
        for (var e : new TreeMap<>(counts).entrySet()) {
            if (kept++ >= MAX_KEYS) {
                break;
            }
            if (!out.isEmpty()) {
                out.append(';');
            }
            out.append(e.getKey()).append('=').append(e.getValue());
        }
        return out.toString();
    }

    /**
     * Valide une déclaration de l'écran : identifiants admis, nombres de 0 à {@link #MAX_PER_REPORT}.
     *
     * @throws IllegalArgumentException identifiant ou nombre hors bornes
     */
    public static Map<String, Integer> validate(Map<String, Integer> raw) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        if (raw.size() > MAX_KEYS) {
            throw new IllegalArgumentException("Trop de scènes.");
        }
        for (var e : raw.entrySet()) {
            Integer n = e.getValue();
            if (e.getKey() == null || !SCENE_ID.matcher(e.getKey()).matches()) {
                throw new IllegalArgumentException("Scène inconnue.");
            }
            if (n == null || n < 0 || n > MAX_PER_REPORT) {
                throw new IllegalArgumentException("Nombre de scènes hors bornes.");
            }
            out.put(e.getKey(), n);
        }
        return out;
    }
}
