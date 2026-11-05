package fr.claudegateway.jarvis.state.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Petits outils de lecture JSON des extracteurs (F-247 / SF-247-04) : jamais d'exception, jamais de valeur inventée. */
final class Json {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    /** Le corps entier lu comme JSON (objet ou tableau), sinon vide. */
    static Optional<JsonNode> parse(String body) {
        if (body == null) {
            return Optional.empty();
        }
        String b = body.strip();
        if (!(b.startsWith("{") || b.startsWith("["))) {
            return Optional.empty();
        }
        try {
            JsonNode n = MAPPER.readTree(b);
            return n == null || n.isMissingNode() ? Optional.empty() : Optional.of(n);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Le texte au chemin donné ({@code "fields", "status", "name"}), {@code null} si absent ou vide. */
    static String text(JsonNode n, String... path) {
        JsonNode cur = n;
        for (String p : path) {
            if (cur == null || cur.isNull() || cur.isMissingNode()) {
                return null;
            }
            cur = cur.get(p);
        }
        if (cur == null || cur.isNull() || cur.isMissingNode() || cur.isContainerNode()) {
            return null;
        }
        String s = cur.asText();
        return s == null || s.isBlank() ? null : s.strip();
    }

    /** Les éléments : le nœud lui-même s'il est un objet, ses éléments s'il est un tableau, ou ceux de {@code field}. */
    static List<JsonNode> items(JsonNode n, String field) {
        List<JsonNode> out = new ArrayList<>();
        Consumer<JsonNode> add = x -> {
            if (x != null && x.isObject()) {
                out.add(x);
            }
        };
        if (n.isArray()) {
            n.forEach(add);
        } else if (field != null && n.has(field) && n.get(field).isArray()) {
            n.get(field).forEach(add);
        } else if (n.isObject()) {
            out.add(n);
        }
        return out.size() > 200 ? out.subList(0, 200) : out;
    }

    /** Ajoute un fait si la valeur existe. */
    static void put(List<StateExtractor.Extracted> out, String kind, String key, String attribute, String value,
            String confidence) {
        if (key != null && !key.isBlank() && value != null && !value.isBlank()) {
            out.add(new StateExtractor.Extracted(kind, key, attribute, value, confidence));
        }
    }
}
