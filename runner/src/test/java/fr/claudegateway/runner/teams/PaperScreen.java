package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Un écran de papier (F-89 / SF-89-06) : des <b>DOM modèles</b> HTML du nouveau Teams v2 — construits
 * pour les tests, <b>à confirmer sur poste réel</b> — sur lesquels la <b>même table de sélecteurs</b> que
 * le script du navigateur est appliquée, par jsoup.
 *
 * <p>Une liste virtualisée est simulée par une suite d'écrans : l'écran 0 est affiché d'abord, chaque
 * défilement fait passer au suivant (plus ancien pour un fil, plus loin pour une transcription).</p>
 *
 * <p>Ce que ce papier ne prouve pas : que le JavaScript réel se comporte comme jsoup, ni que Teams sert
 * ces structures. Il prouve que la table, le refiltrage, le défilement, le recollage et les manques sont
 * justes sur une structure donnée.</p>
 */
final class PaperScreen {

    private final ObjectMapper mapper = new ObjectMapper();
    private final List<Document> screens = new ArrayList<>();
    private int current;
    private int restoredTo = -1;

    static PaperScreen of(String... resources) {
        PaperScreen screen = new PaperScreen();
        for (String resource : resources) {
            screen.screens.add(Jsoup.parse(read(resource)));
        }
        return screen;
    }

    int current() {
        return current;
    }

    int restoredTo() {
        return restoredTo;
    }

    private static String read(String resource) {
        try (InputStream in = PaperScreen.class.getResourceAsStream("/teams/ecran/" + resource)) {
            if (in == null) {
                throw new IllegalStateException("DOM modèle absent : " + resource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Répond à un script d'écran comme le ferait le script générique dans la page. */
    JsonNode evaluate(String expression) {
        JsonNode spec = specOf(expression);
        Document page = screens.get(current);
        Element box = first(page, spec.path("container"));
        if (expression.contains("/*cg-screen:read*/")) {
            ObjectNode out = mapper.createObjectNode();
            out.put("found", box != null);
            if (box == null) {
                return out;
            }
            ArrayNode items = out.putArray("items");
            Elements els = new Elements();
            String wonWith = null;
            for (JsonNode selector : spec.path("item")) {
                els = box.select(selector.asText());
                if (!els.isEmpty()) {
                    wonWith = selector.asText();
                    break;
                }
            }
            JsonNode need = spec.path("require").path(wonWith == null ? "" : wonWith);
            if (need.isArray() && need.size() > 0) {
                Elements kept = new Elements();
                for (Element el : els) {
                    for (JsonNode marker : need) {
                        if (el.is(marker.asText()) || !el.select(marker.asText()).isEmpty()) {
                            kept.add(el);
                            break;
                        }
                    }
                }
                els = kept;
            }
            for (Element el : els) {
                ObjectNode item = items.addObject();
                spec.path("fields").fields().forEachRemaining(entry -> {
                    JsonNode field = entry.getValue();
                    if (field.path("presence").asBoolean(false)) {
                        for (JsonNode selector : field.path("sel")) {
                            if (el.is(selector.asText()) || !el.select(selector.asText()).isEmpty()) {
                                item.put(entry.getKey(), "true");
                                break;
                            }
                        }
                        return;
                    }
                    if (field.path("name").asBoolean(false)) {
                        String name = "";
                        for (JsonNode attribute : field.path("attr")) {
                            if (el.hasAttr(attribute.asText())) {
                                name = el.attr(attribute.asText());
                                if (!name.isEmpty()) {
                                    break;
                                }
                            }
                        }
                        if (name.isEmpty()) {
                            Element child = first(el, field.path("sel"));
                            if (child != null && !unsafe(child)) {
                                name = child.text();
                            }
                        }
                        if (name.isEmpty() && !unsafe(el)) {
                            name = el.text();
                        }
                        if (!name.isEmpty()) {
                            item.put(entry.getKey(), name);
                        }
                        return;
                    }
                    Element target = field.path("sel").size() == 0 ? el : first(el, field.path("sel"));
                    if (target == null || unsafe(target)) {
                        return;
                    }
                    String value = "";
                    if (field.path("attr").size() > 0) {
                        for (JsonNode attribute : field.path("attr")) {
                            if (target.hasAttr(attribute.asText())) {
                                value = target.attr(attribute.asText());
                                value = value.isEmpty() ? "true" : value;
                                break;
                            }
                        }
                    } else {
                        value = target.text();
                    }
                    if (!value.isEmpty()) {
                        item.put(entry.getKey(), value);
                    }
                });
            }
            out.put("atStart", current == screens.size() - 1);
            out.put("atEnd", current == screens.size() - 1);
            return out;
        }
        if (expression.contains("/*cg-screen:scroll-")) {
            ObjectNode out = mapper.createObjectNode();
            out.put("found", box != null);
            boolean moved = box != null && current < screens.size() - 1;
            if (moved) {
                current++;
            }
            out.put("moved", moved);
            return out;
        }
        if (expression.contains("/*cg-screen:position*/")) {
            return mapper.getNodeFactory().numberNode(box == null ? -1 : 100);
        }
        if (expression.contains("/*cg-screen:restore*/")) {
            restoredTo = current;
            current = 0;
            return mapper.getNodeFactory().booleanNode(box != null);
        }
        if (expression.contains("/*cg-screen:control*/")) {
            ObjectNode out = mapper.createObjectNode();
            Element button = first(page, spec.path("buttons"));
            out.put("panel", box != null);
            out.put("present", button != null);
            out.put("disabled", button != null && (button.hasAttr("disabled")
                    || "true".equals(button.attr("aria-disabled"))));
            return out;
        }
        return mapper.nullNode();
    }

    private JsonNode specOf(String expression) {
        int start = expression.indexOf("const spec = ") + "const spec = ".length();
        int end = expression.indexOf("; /*cg-end*/");
        try {
            return mapper.readTree(expression.substring(start, end));
        } catch (Exception e) {
            throw new IllegalStateException("table de sélecteurs illisible", e);
        }
    }

    private static Element first(Element root, JsonNode selectors) {
        for (JsonNode selector : selectors) {
            Elements found = root.select(selector.asText());
            if (!found.isEmpty()) {
                return found.first();
            }
        }
        return null;
    }

    /** Même garde que le script : un champ de saisie, ce qui en contient un, ce qui est dedans. */
    private static boolean unsafe(Element element) {
        if (element.is(TeamsScreen.BANNED) || !element.select(TeamsScreen.BANNED).isEmpty()) {
            return true;
        }
        for (Element parent : element.parents()) {
            if (parent.is(TeamsScreen.BANNED)) {
                return true;
            }
        }
        return false;
    }
}
