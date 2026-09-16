package fr.claudegateway.activity.cra;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
            lines.add(new CraExtraction(client.trim(), days(node), month(node), range(node)));
        }
        return lines;
    }

    /**
     * La plage décrite par le modèle, ou {@code null} si la ligne n'en porte pas. Tolérant : la
     * plage peut être un sous-objet {@code "range"} ou portée à plat sur la ligne. Le serveur
     * convertit ensuite la plage en jours ouvrés (le modèle ne compte jamais).
     */
    private static CraRange range(JsonNode line) {
        JsonNode node = line.get("range");
        if (node == null || !node.isObject()) {
            node = line; // tolérance : preset/fromDay/toDay/from/to portés à plat
        }
        String preset = preset(text(node, "preset", "kind"));
        Integer fromDay = intValue(node, "fromDay", "from_day", "startDay");
        Integer toDay = intValue(node, "toDay", "to_day", "endDay");
        LocalDate from = date(text(node, "from", "start"));
        LocalDate to = date(text(node, "to", "end"));
        CraRange range = new CraRange(preset, fromDay, toDay, from, to);
        return range.isEmpty() ? null : range;
    }

    /** Normalise un preset en {@code FULL_MONTH}/{@code FIRST_HALF}/{@code SECOND_HALF}, ou {@code null}. */
    private static String preset(String raw) {
        if (raw == null) {
            return null;
        }
        String key = raw.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        return switch (key) {
            case "FULL_MONTH", "WHOLE_MONTH", "ALL_MONTH", "MONTH" -> CraRange.FULL_MONTH;
            case "FIRST_HALF", "FIRST_FORTNIGHT" -> CraRange.FIRST_HALF;
            case "SECOND_HALF", "SECOND_FORTNIGHT" -> CraRange.SECOND_HALF;
            default -> null;
        };
    }

    private static Integer intValue(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value == null || value.isNull()) {
                continue;
            }
            if (value.isInt() || value.isLong()) {
                return value.asInt();
            }
            if (value.isTextual()) {
                try {
                    return Integer.valueOf(value.asText().trim());
                } catch (NumberFormatException ignored) {
                    // non numérique : on ignore ce champ
                }
            }
        }
        return null;
    }

    private static LocalDate date(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ignored) {
            return null; // date non ISO : ignorée, la Gateway se rabat sur les autres formes
        }
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
