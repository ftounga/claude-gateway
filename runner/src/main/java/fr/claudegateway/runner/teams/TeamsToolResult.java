package fr.claudegateway.runner.teams;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>L'enveloppe commune</b> de tous les outils de lecture Teams (F-88 / SF-88-01).
 *
 * <p>Elle existe pour une raison précise : <b>F-89 affiche ce que F-88 produit</b>, et les deux
 * avancent en parallèle. Une forme par outil obligerait l'écran à deviner ; une forme unique lui
 * donne un seul gabarit — le résultat, la <b>fenêtre réellement lue</b>, les <b>manques</b>, la
 * <b>santé</b> de l'adaptateur, et la phrase à citer.</p>
 *
 * <p><b>Ce qu'aucun résultat ne peut omettre</b> : {@code text}, {@code window}, {@code gaps},
 * {@code health}. C'est la règle « échouer bruyamment, jamais à moitié faux » rendue structurelle :
 * on ne <b>peut pas</b> rendre une liste sans dire ce qui manque à côté.</p>
 */
final class TeamsToolResult {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ObjectNode node;

    TeamsToolResult(String tool, String adapterVersion, TeamsLinkState state) {
        this.node = mapper.createObjectNode();
        node.put("tool", tool);
        node.put("adapter", adapterVersion == null ? "" : adapterVersion);
        node.put("linkState", (state == null ? TeamsLinkState.LINKED : state).name());
    }

    ObjectNode json() {
        return node;
    }

    ObjectNode put(String field) {
        return node.putObject(field);
    }

    ArrayNode array(String field) {
        return node.putArray(field);
    }

    /** Un champ, s'il porte quelque chose : un champ vide n'apprend rien et encombre le résultat. */
    TeamsToolResult with(String field, String value) {
        if (value != null && !value.isBlank()) {
            node.put(field, value);
        }
        return this;
    }

    /** La fenêtre <b>réellement</b> lue, jamais celle demandée (D4). */
    TeamsToolResult window(TeamsReadWindow window) {
        ObjectNode target = node.putObject("window");
        if (window == null) {
            return this;
        }
        instant(target, "requestedFrom", window.requestedFrom());
        instant(target, "requestedTo", window.requestedTo());
        instant(target, "actualFrom", window.actualFrom());
        instant(target, "actualTo", window.actualTo());
        target.put("cap", window.cap());
        target.put("capReached", window.capReached());
        target.put("reachedStart", window.reachedStartOfConversation());
        target.put("complete", window.fullyCovered());
        target.put("describe", window.describe());
        return this;
    }

    TeamsToolResult gaps(List<TeamsGap> gaps) {
        ArrayNode target = node.putArray("gaps");
        if (gaps == null) {
            return this;
        }
        for (TeamsGap gap : gaps) {
            ObjectNode entry = target.addObject();
            entry.put("kind", gap.kind().name());
            entry.put("label", gap.kind().label());
            entry.put("where", gap.where());
            entry.put("detail", gap.detail());
            entry.put("count", gap.count());
            entry.put("describe", gap.describe());
        }
        return this;
    }

    TeamsToolResult health(TeamsHealth health) {
        ObjectNode target = node.putObject("health");
        TeamsHealth effective = health == null ? TeamsHealth.full(0) : health;
        target.put("verdict", effective.verdict().name());
        target.put("recognizedFields", effective.recognizedFields());
        target.put("expectedFields", effective.expectedFields());
        ArrayNode missing = target.putArray("missingFields");
        effective.missingFields().forEach(missing::add);
        ArrayNode versions = target.putArray("observedApiVersions");
        effective.observedApiVersions().forEach(versions::add);
        target.put("describe", effective.describe());
        return this;
    }

    /** La déclaration de portée (D1), si elle n'a pas déjà été faite pour ce sujet. */
    TeamsToolResult notice(String notice) {
        if (notice != null && !notice.isBlank()) {
            node.put("notice", notice);
        }
        return this;
    }

    /** Ce qui a été fait dans la fenêtre de l'utilisateur — dit, jamais fait en douce. */
    TeamsToolResult viewport(String viewport) {
        if (viewport != null && !viewport.isBlank()) {
            node.put("viewport", viewport);
        }
        return this;
    }

    /**
     * La phrase que l'agent citera. Elle porte le décompte, la fenêtre, le plafond s'il a mordu et
     * les manques — c'est la phrase du cadrage : « 47 messages lus, 3 non reconnus, du 5 au
     * 12 septembre ».
     */
    TeamsToolResult text(String text) {
        StringBuilder full = new StringBuilder(text == null ? "" : text);
        appendIfPresent(full, node.path("viewport").asText(""));
        appendIfPresent(full, node.path("notice").asText(""));
        appendIfPresent(full, node.path("firstUse").asText(""));
        node.put("text", full.toString());
        return this;
    }

    String render() {
        if (!node.has("text")) {
            text("");
        }
        return node.toString();
    }

    static void instant(ObjectNode target, String field, Instant value) {
        if (value == null) {
            target.putNull(field);
        } else {
            target.put(field, value.toString());
        }
    }

    private static void appendIfPresent(StringBuilder text, String extra) {
        if (extra != null && !extra.isBlank()) {
            text.append(System.lineSeparator()).append(extra);
        }
    }
}
