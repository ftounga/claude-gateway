package fr.claudegateway.atelier.nextsteps;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Le bloc « Et maintenant ? » (F-192 / SF-192-01) : les suites que l'agent principal propose à la fin
 * de sa réponse, balisées entre {@code <<suites>>} et {@code <</suites>>}.
 *
 * <p>Le bloc parle au produit, pas au lecteur : il est <b>toujours retiré</b> du texte affiché, même
 * illisible. Seul le contenu validé devient des suites. Une option invalide est ignorée seule ; un
 * bloc illisible ne donne aucune suite — jamais d'exception : un défaut de suggestion ne doit pas
 * coûter le tour.</p>
 *
 * <p>Fonctions pures, sans état : testables sans contexte Spring.</p>
 */
public final class NextStepsBlock {

    /** Options retenues au plus. */
    public static final int MAX_STEPS = 4;
    /** Titre au plus, au-delà tronqué. */
    public static final int MAX_TITLE_CHARS = 80;
    /** Motif au plus, au-delà tronqué. */
    public static final int MAX_WHY_CHARS = 240;
    /** Demande au plus, au-delà l'option est rejetée : une demande tronquée changerait de sens. */
    public static final int MAX_REQUEST_CHARS = 2_000;

    /** Champ du relevé {@code terminal_json} qui porte les suites. */
    public static final String REPORT_FIELD = "nextSteps";

    private static final Pattern OPEN = Pattern.compile("<<\\s*suites\\s*>>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CLOSE = Pattern.compile("<<\\s*/\\s*suites\\s*>>", Pattern.CASE_INSENSITIVE);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NextStepsBlock() {
    }

    /**
     * Ce qui reste du texte une fois les blocs retirés, et les suites retenues.
     *
     * @param text  le texte à afficher, sans aucun bloc de suites
     * @param steps les suites validées, vide si aucune
     */
    public record Extraction(String text, List<NextStep> steps) {
    }

    /**
     * Retire tous les blocs de suites du texte et lit le <b>dernier</b> bloc lisible.
     *
     * <p>Une ouverture sans fermeture (réponse tronquée) retire tout ce qui la suit : le marqueur brut
     * n'est jamais montré.</p>
     */
    public static Extraction extract(String raw) {
        if (raw == null || raw.isEmpty()) {
            return new Extraction(raw, List.of());
        }
        StringBuilder kept = new StringBuilder();
        List<NextStep> steps = List.of();
        int from = 0;
        boolean removed = false;
        Matcher open = OPEN.matcher(raw);
        while (from <= raw.length() && open.find(from)) {
            removed = true;
            kept.append(raw, from, open.start());
            Matcher close = CLOSE.matcher(raw);
            if (!close.find(open.end())) {
                from = raw.length();
                break;
            }
            List<NextStep> parsed = parse(raw.substring(open.end(), close.start()));
            if (!parsed.isEmpty()) {
                steps = parsed;
            }
            from = close.end();
        }
        if (!removed) {
            return new Extraction(raw, List.of());
        }
        if (from < raw.length()) {
            kept.append(raw, from, raw.length());
        }
        String text = kept.toString().replaceAll("\\n{3,}", "\n\n").strip();
        return new Extraction(text, steps);
    }

    /** Lit le corps d'un bloc : un tableau JSON d'options. Illisible ⇒ aucune suite. */
    static List<NextStep> parse(String body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(stripCodeFence(body.strip()));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return List.of();
        }
        if (root == null || !root.isArray()) {
            return List.of();
        }
        List<NextStep> steps = new ArrayList<>();
        for (JsonNode node : root) {
            if (steps.size() >= MAX_STEPS) {
                break;
            }
            NextStep step = toStep(node);
            if (step != null) {
                steps.add(step);
            }
        }
        return List.copyOf(steps);
    }

    /** Le modèle entoure parfois le JSON d'une clôture Markdown : on la tolère. */
    private static String stripCodeFence(String body) {
        if (!body.startsWith("```")) {
            return body;
        }
        String withoutOpen = body.replaceFirst("^```[a-zA-Z]*\\s*", "");
        return withoutOpen.endsWith("```") ? withoutOpen.substring(0, withoutOpen.length() - 3) : withoutOpen;
    }

    private static NextStep toStep(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        String title = text(node, "titre", "title");
        String why = text(node, "pourquoi", "why");
        String request = text(node, "demande", "request");
        NextStep.Label label = label(text(node, "etiquette", "étiquette", "label"));
        if (title.isEmpty() || why.isEmpty() || request.isEmpty() || label == null
                || request.length() > MAX_REQUEST_CHARS) {
            return null;
        }
        return new NextStep(truncate(title, MAX_TITLE_CHARS), truncate(why, MAX_WHY_CHARS), label, request);
    }

    private static String text(JsonNode node, String... names) {
        for (String name : names) {
            JsonNode value = node.get(name);
            if (value != null && value.isTextual()) {
                return value.asText().strip();
            }
        }
        return "";
    }

    /** Étiquette normalisée : casse et accents libres. Inconnue ⇒ {@code null}. */
    static NextStep.Label label(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String plain = Normalizer.normalize(raw.strip(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toUpperCase(Locale.ROOT);
        try {
            return NextStep.Label.valueOf(plain);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1).strip() + "…";
    }

    /**
     * Range les suites dans le relevé du message ({@code terminal_json}). Sans suite, le relevé
     * traverse inchangé. Sans relevé, un document minimal est créé. Un relevé illisible est laissé
     * tel quel : un défaut d'affichage ne doit jamais faire perdre le tour.
     */
    public static String attachTo(String reportJson, List<NextStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return reportJson;
        }
        ObjectNode report;
        if (reportJson == null || reportJson.isBlank()) {
            report = MAPPER.createObjectNode();
        } else {
            try {
                JsonNode parsed = MAPPER.readTree(reportJson);
                if (!(parsed instanceof ObjectNode object)) {
                    return reportJson;
                }
                report = object;
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                return reportJson;
            }
        }
        ArrayNode array = report.putArray(REPORT_FIELD);
        for (NextStep step : steps) {
            array.add(MAPPER.valueToTree(step));
        }
        try {
            return MAPPER.writeValueAsString(report);
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return reportJson;
        }
    }
}
