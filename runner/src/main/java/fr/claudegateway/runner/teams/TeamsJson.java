package fr.claudegateway.runner.teams;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Lecture <b>défensive</b> de champs JSON (F-87 / SF-87-01).
 *
 * <p>Toute méthode rend une valeur sûre plutôt que de lever : c'est la seule façon de garantir qu'un
 * corps de réponse inattendu produise un <b>manque</b> nommé, et non une exception qui remonterait
 * jusqu'à l'utilisateur sous la forme d'une panne incompréhensible.</p>
 *
 * <p>Classe non publique, comme le reste du savoir Teams.</p>
 */
final class TeamsJson {

    private TeamsJson() {
    }

    /** Premier champ textuel non vide parmi les noms donnés, ou {@code ""}. */
    static String text(JsonNode node, String... names) {
        if (node == null) {
            return "";
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isValueNode() && !value.asText().isBlank()) {
                return value.asText().strip();
            }
        }
        return "";
    }

    /** Premier champ booléen parmi les noms donnés, ou {@code false}. */
    static boolean flag(JsonNode node, String... names) {
        if (node == null) {
            return false;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isBoolean()) {
                return value.asBoolean();
            }
            if (value != null && value.isTextual()) {
                String raw = value.asText().strip().toLowerCase(Locale.ROOT);
                if (raw.equals("true") || raw.equals("false")) {
                    return raw.equals("true");
                }
            }
        }
        return false;
    }

    /** Premier champ numérique parmi les noms donnés, ou {@code fallback}. */
    static long number(JsonNode node, long fallback, String... names) {
        if (node == null) {
            return fallback;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isNumber()) {
                return value.asLong();
            }
            if (value != null && value.isTextual()) {
                try {
                    return Long.parseLong(value.asText().strip());
                } catch (NumberFormatException ignored) {
                    // Champ textuel non numérique : on continue, on n'invente pas.
                }
            }
        }
        return fallback;
    }

    /**
     * Instant lu depuis un champ, ou {@code null} — <b>jamais</b> « maintenant » par défaut : un
     * horodatage inventé est exactement le genre de demi-vérité qui rend un compte rendu faux.
     *
     * <p>Trois formes acceptées : ISO-8601 avec décalage, ISO-8601 en {@code Z}, et
     * millisecondes depuis l'époque (Teams mélange les trois selon les services).</p>
     */
    static Instant instant(JsonNode node, String... names) {
        String raw = text(node, names);
        if (raw.isEmpty()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (DateTimeParseException first) {
            try {
                return Instant.parse(raw);
            } catch (DateTimeParseException second) {
                try {
                    long epochMillis = Long.parseLong(raw);
                    return epochMillis > 0 ? Instant.ofEpochMilli(epochMillis) : null;
                } catch (NumberFormatException third) {
                    return null;
                }
            }
        }
    }

    /**
     * Champ qui porte parfois un objet, parfois une chaîne contenant du JSON (Teams fait les deux
     * pour les mentions et les fichiers). Rend {@code null} si rien n'est exploitable.
     */
    static JsonNode embedded(com.fasterxml.jackson.databind.ObjectMapper mapper, JsonNode node,
            String... names) {
        if (node == null) {
            return null;
        }
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isArray() || value.isObject()) {
                return value;
            }
            if (value.isTextual() && !value.asText().isBlank()) {
                try {
                    return mapper.readTree(value.asText());
                } catch (Exception ignored) {
                    // Chaîne qui n'est pas du JSON : ce champ n'est pas exploitable, on continue.
                }
            }
        }
        return null;
    }

    /**
     * Texte plat depuis du HTML : les balises sautent, les entités courantes sont rendues, les
     * sauts de ligne structurants sont conservés. Volontairement simple — il ne s'agit pas
     * d'afficher une page, mais d'écrire un compte rendu lisible.
     */
    static String plainText(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String text = html
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</p\\s*>", "\n")
                .replaceAll("(?i)</div\\s*>", "\n")
                .replaceAll("(?i)</li\\s*>", "\n")
                .replaceAll("<[^>]*>", "");
        text = text.replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'");
        return text.replaceAll("[ \\t]+", " ").replaceAll("\n{3,}", "\n\n").strip();
    }
}
