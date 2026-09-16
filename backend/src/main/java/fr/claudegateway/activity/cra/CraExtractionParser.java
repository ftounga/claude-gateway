package fr.claudegateway.activity.cra;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Traduit la réponse <b>texte</b> du modèle en lignes {@link CraExtraction} (F-124 / SF-124-03).
 *
 * <p>Fonction pure et <b>tolérante</b> : le modèle est prié de rendre un tableau JSON
 * {@code [{"client","days","month"}]}, mais il peut l'entourer de texte ou d'un bloc de code. On
 * isole le premier tableau JSON, on le lit, et on ignore les entrées inexploitables — jamais
 * d'exception qui casserait le tour. La Gateway rapproche et valide ensuite (elle ne fait pas
 * confiance à ce qui sort d'ici).</p>
 */
public class CraExtractionParser {

    private final ObjectMapper mapper = new ObjectMapper();

    /** Les lignes extraites du texte du modèle, ou une liste vide si rien n'est exploitable. */
    public List<CraExtraction> parse(String modelText) {
        List<CraExtraction> lines = new ArrayList<>();
        String json = isolateJsonArray(modelText);
        if (json == null) {
            return lines;
        }
        JsonNode root;
        try {
            root = mapper.readTree(json);
        } catch (Exception e) {
            return lines;
        }
        if (root == null || !root.isArray()) {
            return lines;
        }
        for (JsonNode node : root) {
            if (!node.isObject()) {
                continue;
            }
            String client = text(node, "client", "poste", "name");
            if (client == null || client.isBlank()) {
                continue; // une ligne sans nom n'est pas exploitable
            }
            lines.add(new CraExtraction(client.trim(), days(node), month(node)));
        }
        return lines;
    }

    /** Le premier tableau JSON du texte (du premier {@code [} au {@code ]} équilibré), ou {@code null}. */
    private static String isolateJsonArray(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('[');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return text.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && value.isValueNode() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private static BigDecimal days(JsonNode node) {
        for (String field : new String[] { "days", "jours", "d" }) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isNumber()) {
                return value.decimalValue();
            }
            if (value.isTextual()) {
                try {
                    return new BigDecimal(value.asText().trim().replace(',', '.'));
                } catch (NumberFormatException ignored) {
                    // valeur non numérique : on laisse la Gateway refuser la ligne (days null)
                }
            }
        }
        return null;
    }

    private static String month(JsonNode node) {
        String raw = text(node, "month", "mois");
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        return trimmed.matches("^\\d{4}-(0[1-9]|1[0-2])$") ? trimmed : null;
    }
}
