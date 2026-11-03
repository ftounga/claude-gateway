package fr.claudegateway.jarvis.mail;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <b>La validation stricte de la sortie du tri</b> (F-242 / SF-242-03, D6, D9 b) — un tableau JSON ; seuls les jetons du
 * lot sont lus ; une classe hors enum vaut « à lire » ; la raison est bornée à 8 mots et 60 caractères. Toute sortie
 * illisible rend une carte vide : l'appelant classe alors tout le lot « à lire », <b>jamais</b> « à ignorer ».
 */
public final class JarvisMailTriageParser {

    /** Un classement validé. */
    public record Verdict(JarvisMailItem.Triage triage, String reason) {
    }

    public static final String UNSURE = "Tri incertain";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_WORDS = 8;
    private static final int MAX_CHARS = 60;

    private JarvisMailTriageParser() {
    }

    /** Les classements lus, par jeton ; vide si la sortie est illisible. */
    public static Map<String, Verdict> parse(String output, Set<String> tokens) {
        Map<String, Verdict> out = new HashMap<>();
        if (output == null) {
            return out;
        }
        int start = output.indexOf('[');
        int end = output.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return out;
        }
        JsonNode array;
        try {
            array = JSON.readTree(output.substring(start, end + 1));
        } catch (Exception e) {
            return out;
        }
        if (array == null || !array.isArray()) {
            return out;
        }
        for (JsonNode node : array) {
            if (!node.isObject()) {
                continue;
            }
            String id = node.path("id").asText("").strip();
            if (!tokens.contains(id) || out.containsKey(id)) {
                continue;
            }
            JarvisMailItem.Triage triage = triage(node.path("classe").asText(""));
            String reason = triage == null ? UNSURE : reason(node.path("raison").asText(""));
            out.put(id, new Verdict(triage == null ? JarvisMailItem.Triage.A_LIRE : triage, reason));
        }
        return out;
    }

    static JarvisMailItem.Triage triage(String raw) {
        String s = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT).replace(' ', '_').replace('À', 'A');
        return switch (s) {
            case "A_TRAITER" -> JarvisMailItem.Triage.A_TRAITER;
            case "A_LIRE" -> JarvisMailItem.Triage.A_LIRE;
            case "A_IGNORER" -> JarvisMailItem.Triage.A_IGNORER;
            default -> null;
        };
    }

    /** Texte seul, 8 mots et 60 caractères au plus ; aucune balise, aucun lien. */
    static String reason(String raw) {
        String s = raw == null ? "" : raw.replaceAll("<[^>]*>", " ").replaceAll("https?://\\S+", " ")
                .replaceAll("[\\p{Cntrl}\"`]", " ").replaceAll("\\s+", " ").strip();
        if (s.isEmpty()) {
            return UNSURE;
        }
        String[] words = s.split(" ");
        if (words.length > MAX_WORDS) {
            s = String.join(" ", java.util.Arrays.copyOf(words, MAX_WORDS));
        }
        return s.length() <= MAX_CHARS ? s : s.substring(0, MAX_CHARS).strip();
    }
}
