package fr.claudegateway.atelier.veille;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <b>La liste normalisée que rend une sonde</b> (F-230 / SF-230-01, D2) :
 * {@code [{"id","titre","statut","assigne","mis_a_jour","dernier_commentaire"}]} sur la sortie standard. Calcul
 * pur : lecture tolérante (bruit autour du JSON, clés accentuées), bornes, résumé par statut.
 */
public final class JarvisWatchItems {

    /** Éléments au plus : au-delà, la sonde regarde trop large. */
    public static final int MAX_ITEMS = 500;
    static final int MAX_ID = 80;
    static final int MAX_TITLE = 200;
    static final int MAX_SHORT = 80;
    static final int MAX_COMMENT = 300;
    static final int MAX_SAMPLE = 5;
    static final int MAX_STATUSES_IN_SUMMARY = 4;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Un élément suivi (ticket, carte…). Tous les champs sauf {@code id} peuvent être vides. */
    public record Item(String id, String title, String status, String assignee, String updatedAt, String lastComment) {
    }

    /** Le résultat d'une lecture : les éléments, ou la raison lisible pour laquelle la sortie est refusée. */
    public record Parsed(List<Item> items, String error) {

        public boolean ok() {
            return error == null;
        }
    }

    private JarvisWatchItems() {
    }

    /**
     * Lit la sortie d'une sonde. Tolère du texte autour du tableau JSON (un message d'outil, une ligne vide) ;
     * refuse un objet seul, un tableau d'autre chose, un élément sans {@code id}, plus de {@link #MAX_ITEMS}.
     */
    public static Parsed parse(String output) {
        if (output == null || output.isBlank()) {
            return new Parsed(List.of(), "La sonde n'a rien écrit : elle doit écrire la liste en JSON sur la sortie "
                    + "standard.");
        }
        Optional<JsonNode> array = arrayIn(output.strip());
        if (array.isEmpty()) {
            return new Parsed(List.of(), "La sortie de la sonde n'est pas une liste JSON "
                    + "[{\"id\", \"titre\", \"statut\", \"assigne\", \"mis_a_jour\", \"dernier_commentaire\"}].");
        }
        JsonNode nodes = array.get();
        if (nodes.size() > MAX_ITEMS) {
            return new Parsed(List.of(), "La sonde rend " + nodes.size() + " éléments : " + MAX_ITEMS
                    + " au plus — resserre le périmètre.");
        }
        List<Item> items = new ArrayList<>(nodes.size());
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode node : nodes) {
            if (!node.isObject()) {
                return new Parsed(List.of(), "Chaque élément de la liste doit être un objet JSON.");
            }
            String id = bounded(text(node, "id"), MAX_ID);
            if (id.isEmpty()) {
                return new Parsed(List.of(), "Un élément de la liste n'a pas d'« id ».");
            }
            if (!ids.add(id)) {
                continue; // un doublon de la plateforme ne fait pas deux éléments
            }
            items.add(new Item(id,
                    bounded(text(node, "titre", "title"), MAX_TITLE),
                    bounded(text(node, "statut", "status"), MAX_SHORT),
                    bounded(text(node, "assigne", "assigné", "assignee"), MAX_SHORT),
                    bounded(text(node, "mis_a_jour", "mis_à_jour", "updated"), MAX_SHORT),
                    bounded(text(node, "dernier_commentaire", "last_comment"), MAX_COMMENT)));
        }
        return new Parsed(List.copyOf(items), null);
    }

    /** « 12 éléments : 5 en cours, 2 bloqués, 5 ready to dev » — les statuts les plus nombreux d'abord. */
    public static String summary(List<Item> items) {
        if (items == null || items.isEmpty()) {
            return "Aucun élément dans le périmètre pour l'instant.";
        }
        Map<String, Integer> byStatus = statusCounts(items);
        List<Map.Entry<String, Integer>> sorted = new ArrayList<>(byStatus.entrySet());
        sorted.sort(Map.Entry.<String, Integer>comparingByValue(Comparator.reverseOrder()));
        StringBuilder line = new StringBuilder();
        line.append(items.size()).append(items.size() == 1 ? " élément" : " éléments");
        int shown = 0;
        for (Map.Entry<String, Integer> entry : sorted) {
            if (shown == MAX_STATUSES_IN_SUMMARY) {
                line.append(", …");
                break;
            }
            line.append(shown == 0 ? " : " : ", ").append(entry.getValue()).append(' ').append(entry.getKey());
            shown++;
        }
        String result = line.toString();
        return result.length() <= 500 ? result : result.substring(0, 499) + "…";
    }

    /** Le décompte par statut, dans l'ordre d'apparition ; un statut vide se compte « sans statut ». */
    public static Map<String, Integer> statusCounts(List<Item> items) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Item item : items) {
            String status = item.status().isEmpty() ? "sans statut" : item.status().toLowerCase(Locale.ROOT);
            counts.merge(status, 1, Integer::sum);
        }
        return counts;
    }

    /** Quelques exemples à montrer au PO : « PROJ-12 Alarme EDENRED ». */
    public static List<String> sample(List<Item> items) {
        return items.stream().limit(MAX_SAMPLE)
                .map(item -> item.title().isEmpty() ? item.id() : item.id() + " " + item.title())
                .toList();
    }

    /** Sérialise la liste (instantané, SF-230-02). */
    public static String toJson(List<Item> items) {
        try {
            return MAPPER.writeValueAsString(items);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Instantané non sérialisable", e);
        }
    }

    /** Relit un instantané sérialisé par {@link #toJson} ; illisible ⇒ vide. */
    public static List<Item> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return List.of(MAPPER.readValue(json, Item[].class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return List.of();
        }
    }

    private static Optional<JsonNode> arrayIn(String output) {
        Optional<JsonNode> whole = readArray(output);
        if (whole.isPresent()) {
            return whole;
        }
        // Du bruit autour du JSON (une ligne d'avertissement d'un outil) : le tableau le plus large.
        int start = output.indexOf('[');
        int end = output.lastIndexOf(']');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        return readArray(output.substring(start, end + 1));
    }

    private static Optional<JsonNode> readArray(String text) {
        try {
            JsonNode node = MAPPER.readTree(text);
            return node != null && node.isArray() ? Optional.of(node) : Optional.empty();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Optional.empty();
        }
    }

    private static String text(JsonNode node, String... keys) {
        for (String key : keys) {
            JsonNode value = node.get(key);
            if (value != null && !value.isNull()) {
                if (value.isValueNode()) {
                    return value.asText();
                }
                // Un objet (assigné {displayName…}) : sa forme courte plutôt qu'un refus.
                JsonNode name = value.get("displayName") != null ? value.get("displayName") : value.get("name");
                return name != null && name.isValueNode() ? name.asText() : value.toString();
            }
        }
        return "";
    }

    private static String bounded(String value, int max) {
        String v = value == null ? "" : value.strip().replaceAll("\\s+", " ");
        return v.length() <= max ? v : v.substring(0, max - 1) + "…";
    }
}
