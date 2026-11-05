package fr.claudegateway.jarvis.state.extract;

import static fr.claudegateway.jarvis.state.JarvisStateVocabulary.MOYENNE;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>Les sorties JSON génériques</b> (F-247 / SF-247-17) : la sortie <b>entière</b> d'une commande — script maison
 * ({@code python3 …}), outil résolu derrière un alias — est un objet JSON, ou un tableau d'objets, dont chaque élément
 * porte <b>une identité</b> ({@code key}, {@code name}, {@code arn}, {@code slug}, {@code id}) et au moins un champ
 * d'état reconnu au premier niveau ({@code status}, {@code state}, {@code version}, {@code url}). Famille
 * {@code OTHER}, confiance {@code MOYENNE}, clé {@code JSON:<identité>}. Repli appliqué seulement quand aucun
 * extracteur spécialisé ni l'API générique n'a rien lu, et jamais à une commande de fichiers ({@code cat x.json}).
 * Rien n'est déduit : une valeur absente, vide, composée ou trop longue n'est pas écrite.
 */
final class GenericJsonExtractor implements StateExtractor {

    static final int MAX_ITEMS = 50;
    private static final String[] IDENTITY = {"key", "name", "arn", "slug", "id"};

    @Override
    public String name() {
        return "json";
    }

    @Override
    public boolean accepts(String command) {
        return command != null && !command.isBlank() && !CommandShape.fileOnly(command);
    }

    @Override
    public List<Extracted> extract(String command, String body) {
        List<Extracted> out = new ArrayList<>();
        Json.parse(body).ifPresent(root -> {
            List<JsonNode> items = new ArrayList<>();
            if (root.isObject()) {
                items.add(root);
            } else if (root.isArray()) {
                if (root.size() > MAX_ITEMS) {
                    return;
                }
                root.forEach(n -> {
                    if (n.isObject()) {
                        items.add(n);
                    }
                });
            }
            for (JsonNode item : items) {
                String id = identity(item);
                if (id == null) {
                    continue;
                }
                String key = "JSON:" + id;
                String status = scalar(item, "status");
                String state = scalar(item, "state");
                Json.put(out, "OTHER", key, "status", status, MOYENNE);
                Json.put(out, "OTHER", key, "state", state, MOYENNE);
                Json.put(out, "OTHER", key, "version", scalar(item, "version"), MOYENNE);
                String url = scalar(item, "url");
                Json.put(out, "OTHER", key, "url", url != null && url.matches("https?://\\S+") ? url : null, MOYENNE);
            }
        });
        return out;
    }

    /** L'identité d'un élément : la première trouvée, porteuse d'au moins une lettre, ≤ 120 caractères. */
    static String identity(JsonNode item) {
        for (String f : IDENTITY) {
            String v = scalar(item, f, 120);
            if (v != null && v.matches(".*[A-Za-z].*") && !v.contains("\n")) {
                return v;
            }
        }
        return null;
    }

    private static String scalar(JsonNode item, String field) {
        return scalar(item, field, 120);
    }

    private static String scalar(JsonNode item, String field, int max) {
        JsonNode v = item.get(field);
        if (v == null || !v.isValueNode() || v.isNull() || v.isBoolean()) {
            return null;
        }
        String s = v.asText().strip();
        return s.isEmpty() || s.length() > max ? null : s;
    }
}
