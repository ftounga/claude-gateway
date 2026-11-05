package fr.claudegateway.jarvis.state.capture;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <b>La garde de la lecture d'une capture</b> (F-247 / SF-247-12) : la sortie du modèle n'est gardée que si elle est
 * exactement conforme. En cas de doute, <b>rien</b> n'est gardé (statut {@link Status#ILLISIBLE}).
 */
public final class CaptureReadParser {

    public static final int MAX_MESSAGES = 60;
    public static final int MAX_TEXT = 2_000;
    public static final int MAX_SPEAKER = 120;
    public static final int MAX_TITLE = 300;
    public static final int MAX_STAMP = 40;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** L'issue d'une lecture. */
    public enum Status {
        /** Une conversation, lue et conforme. */
        LUE,
        /** Le modèle dit que ce n'est pas une conversation : rien n'est gardé. */
        NON_CONVERSATION,
        /** Sortie non conforme : rien n'est gardé. */
        ILLISIBLE
    }

    /** Un message tel que lu (avant résolution de la date et de la personne). */
    public record Line(String speaker, String stamp, String text) {
    }

    /** La lecture. */
    public record Reading(Status status, String platform, String title, List<Line> lines) {

        static Reading of(Status status) {
            return new Reading(status, null, null, List.of());
        }
    }

    private CaptureReadParser() {
    }

    public static Reading parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return Reading.of(Status.ILLISIBLE);
        }
        String json = raw.strip();
        if (json.startsWith("```")) {
            int nl = json.indexOf('\n');
            int end = json.lastIndexOf("```");
            if (nl < 0 || end <= nl) {
                return Reading.of(Status.ILLISIBLE);
            }
            json = json.substring(nl + 1, end).strip();
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception e) {
            return Reading.of(Status.ILLISIBLE);
        }
        if (root == null || !root.isObject() || !root.path("conversation").isBoolean()) {
            return Reading.of(Status.ILLISIBLE);
        }
        if (!root.path("conversation").asBoolean()) {
            return Reading.of(Status.NON_CONVERSATION);
        }
        String platform = platform(root.path("plateforme").asText(""));
        JsonNode messages = root.path("messages");
        if (platform == null || !messages.isArray() || messages.isEmpty() || messages.size() > MAX_MESSAGES) {
            return Reading.of(Status.ILLISIBLE);
        }
        List<Line> lines = new ArrayList<>();
        for (JsonNode m : messages) {
            if (!m.isObject() || !m.path("interlocuteur").isTextual() || !m.path("texte").isTextual()) {
                return Reading.of(Status.ILLISIBLE);
            }
            String speaker = clean(m.path("interlocuteur").asText());
            String text = m.path("texte").asText().strip();
            String stamp = m.path("horodatage_visible").isTextual() ? clean(m.path("horodatage_visible").asText()) : "";
            if (speaker.isEmpty() || speaker.length() > MAX_SPEAKER || text.isEmpty() || text.length() > MAX_TEXT
                    || stamp.length() > MAX_STAMP) {
                return Reading.of(Status.ILLISIBLE);
            }
            lines.add(new Line(speaker, stamp, text));
        }
        String title = root.path("titre").isTextual() ? clean(root.path("titre").asText()) : "";
        if (title.length() > MAX_TITLE) {
            title = title.substring(0, MAX_TITLE - 1) + "…";
        }
        return new Reading(Status.LUE, platform, title.isEmpty() ? null : title, List.copyOf(lines));
    }

    private static String platform(String raw) {
        String p = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        return switch (p) {
            case "teams", "mail", "autre" -> p.toUpperCase(Locale.ROOT);
            default -> null;
        };
    }

    private static String clean(String s) {
        return s == null ? "" : s.strip().replaceAll("[\\r\\n\\t]+", " ");
    }
}
