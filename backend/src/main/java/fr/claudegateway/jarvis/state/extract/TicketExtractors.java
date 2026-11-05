package fr.claudegateway.jarvis.state.extract;

import static fr.claudegateway.jarvis.state.JarvisStateVocabulary.HAUTE;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>Extracteurs des tickets</b> (F-247 / SF-247-04) : Jira (API REST {@code /rest/api/2|3/issue}, {@code /search},
 * CLI {@code jira}/{@code acli} en sortie JSON) et Confluence ({@code /rest/api/content}). Confiance {@code HAUTE} :
 * JSON d'API parsé.
 */
final class TicketExtractors {

    private TicketExtractors() {
    }

    static final Pattern JIRA_KEY = Pattern.compile("^[A-Z][A-Z0-9]{1,9}-\\d{1,7}$");

    /** Jira : un ticket ou une recherche. */
    static final class Jira implements StateExtractor {
        private static final Pattern CMD = Pattern.compile("/rest/api/(2|3|latest)/(issue|search)|\\bjira\\b.*\\b(--output[ =]json|--raw|-r\\b|--json)|\\bacli\\b.*\\bjira\\b.*--json");

        @Override
        public String name() {
            return "jira";
        }

        @Override
        public boolean accepts(String command) {
            return CMD.matcher(command).find();
        }

        @Override
        public List<Extracted> extract(String command, String body) {
            List<Extracted> out = new ArrayList<>();
            Json.parse(body).ifPresent(root -> {
                for (JsonNode issue : Json.items(root, "issues")) {
                    String key = Json.text(issue, "key");
                    if (key == null || !JIRA_KEY.matcher(key).matches() || !issue.has("fields")) {
                        continue;
                    }
                    String k = "JIRA:" + key;
                    JsonNode f = issue.get("fields");
                    Json.put(out, "TICKET", k, "status", Json.text(f, "status", "name"), HAUTE);
                    Json.put(out, "TICKET", k, "assignee", Json.text(f, "assignee", "displayName"), HAUTE);
                    Json.put(out, "TICKET", k, "title", Json.text(f, "summary"), HAUTE);
                    Json.put(out, "TICKET", k, "priority", Json.text(f, "priority", "name"), HAUTE);
                    Json.put(out, "TICKET", k, "updated_at", Json.text(f, "updated"), HAUTE);
                    sprint(f).ifPresent(s -> Json.put(out, "TICKET", k, "sprint", s, HAUTE));
                    blockedBy(f).ifPresent(b -> Json.put(out, "TICKET", k, "blocked_by", b, HAUTE));
                }
            });
            return out;
        }

        private static java.util.Optional<String> sprint(JsonNode f) {
            JsonNode s = f.get("sprint");
            if (s != null && s.isObject()) {
                return java.util.Optional.ofNullable(Json.text(s, "name"));
            }
            return java.util.Optional.empty();
        }

        /** Les tickets qui bloquent celui-ci ({@code issuelinks} « is blocked by »), lus tels quels. */
        private static java.util.Optional<String> blockedBy(JsonNode f) {
            JsonNode links = f.get("issuelinks");
            if (links == null || !links.isArray()) {
                return java.util.Optional.empty();
            }
            List<String> keys = new ArrayList<>();
            for (JsonNode l : links) {
                String inward = Json.text(l, "type", "inward");
                String other = Json.text(l, "inwardIssue", "key");
                if (inward != null && other != null && inward.toLowerCase().contains("blocked")) {
                    keys.add(other);
                }
            }
            return keys.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(String.join(", ", keys));
        }
    }

    /** Confluence : une page ou une liste de pages. */
    static final class Confluence implements StateExtractor {
        private static final Pattern CMD = Pattern.compile("/rest/api/content|/wiki/api/v2/pages");

        @Override
        public String name() {
            return "confluence";
        }

        @Override
        public boolean accepts(String command) {
            return CMD.matcher(command).find();
        }

        @Override
        public List<Extracted> extract(String command, String body) {
            List<Extracted> out = new ArrayList<>();
            Json.parse(body).ifPresent(root -> {
                for (JsonNode page : Json.items(root, "results")) {
                    String id = Json.text(page, "id");
                    String type = Json.text(page, "type");
                    if (id == null || !id.matches("\\d{1,20}") || (type != null && !"page".equals(type)
                            && !"blogpost".equals(type))) {
                        continue;
                    }
                    String k = "CONFLUENCE:" + id;
                    Json.put(out, "PAGE", k, "title", Json.text(page, "title"), HAUTE);
                    Json.put(out, "PAGE", k, "status", Json.text(page, "status"), HAUTE);
                    String version = Json.text(page, "version", "number");
                    Json.put(out, "PAGE", k, "page_version", version, HAUTE);
                    Json.put(out, "PAGE", k, "updated_at", Json.text(page, "version", "when"), HAUTE);
                    String base = Json.text(page, "_links", "base");
                    String webui = Json.text(page, "_links", "webui");
                    if (base != null && webui != null && base.startsWith("https://")) {
                        Json.put(out, "PAGE", k, "url", base + webui, HAUTE);
                    }
                }
            });
            return out;
        }
    }
}
