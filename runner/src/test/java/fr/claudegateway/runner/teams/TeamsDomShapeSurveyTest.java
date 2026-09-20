package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.diag.RunnerDiag;
import fr.claudegateway.runner.diag.RunnerDiagEvent;
import fr.claudegateway.runner.diag.RunnerDiagLevel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-89 / SF-89-16 — le <b>pilote</b> du relevé de forme du DOM, gardé par le niveau F-132. Ce que ce
 * test tient : l'<b>inertie</b> hors {@code DEBUG} (rien ne part), le relevé des deux vues à
 * {@code DEBUG}, la remise de la vue, l'anti-rafale, et — non négociable — <b>aucune valeur ne fuit</b>
 * dans les événements émis, même quand le navigateur de papier rend un DOM porteur de noms.
 */
class TeamsDomShapeSurveyTest {

    private final List<PageActions.GestureRecord> journal = new ArrayList<>();
    private final Consumer<PageActions.GestureRecord> sink = journal::add;

    @BeforeEach
    void arrange() {
        RunnerDiag.reset();
        TeamsDomShapeSurvey.resetThrottle();
    }

    @AfterEach
    void cleanup() {
        RunnerDiag.reset();
        TeamsDomShapeSurvey.resetThrottle();
    }

    @Test
    @DisplayName("Inertie (CA1) : hors DEBUG, aucun script, aucune navigation, aucun événement")
    void inert_when_not_debug() {
        RunnerDiag.setLevel(RunnerDiagLevel.INFO);
        StubConnection cdp = new StubConnection("https://teams.microsoft.com/v2/#/conversations");
        PageActions actions = new PageActions(cdp, noSleep(), sink);

        TeamsDomShapeSurvey.run(actions, noSleep());

        assertTrue(cdp.sent.isEmpty(), "aucune commande CDP hors DEBUG : " + cdp.sent);
        assertTrue(RunnerDiag.isEmpty(), "aucun événement de forme hors DEBUG");
    }

    @Test
    @DisplayName("Relevé (CA2/CA3/CA9) : à DEBUG, la liste ET un fil sont relevés, la vue est remise")
    void surveys_list_and_thread_and_restores() {
        RunnerDiag.setLevel(RunnerDiagLevel.DEBUG);
        String before = "https://teams.microsoft.com/v2/#/conversations";
        StubConnection cdp = new StubConnection(before);
        PageActions actions = new PageActions(cdp, noSleep(), sink);

        TeamsDomShapeSurvey.run(actions, noSleep());

        List<RunnerDiagEvent> events = drainShape();
        assertTrue(codes(events).contains(TeamsDomShapeSurvey.LIST_CODE), "la liste est relevée");
        assertTrue(codes(events).contains(TeamsDomShapeSurvey.THREAD_CODE), "un fil est relevé");
        // Deux en-têtes (une par vue) + des nœuds.
        long headers = events.stream().filter(e -> "header".equals(e.fields().get("kind"))).count();
        assertEquals(2, headers, "un en-tête par vue");
        assertTrue(events.stream().anyMatch(e -> String.valueOf(e.msg()).contains("chat-list")),
                "la forme de la liste porte ses data-tid");
        // La vue a été remise là où elle était.
        assertEquals(before, cdp.url, "la vue est remise après le relevé");
    }

    @Test
    @DisplayName("Vie privée bout-en-bout : aucun nom, message ou id ne fuit dans les événements émis")
    void no_value_leaks_end_to_end() {
        RunnerDiag.setLevel(RunnerDiagLevel.DEBUG);
        StubConnection cdp = new StubConnection("https://teams.microsoft.com/v2/#/conversations");
        PageActions actions = new PageActions(cdp, noSleep(), sink);

        TeamsDomShapeSurvey.run(actions, noSleep());

        String all = drainShape().stream()
                .map(e -> e.msg() + " " + e.fields())
                .reduce("", (a, b) -> a + " || " + b);
        for (String forbidden : List.of("Jean Dupont", "bonjour", "secret", "thread.v2",
                "jean.dupont@client.fr")) {
            assertFalse(all.contains(forbidden), "une valeur a fui : « " + forbidden + " » — " + all);
        }
    }

    @Test
    @DisplayName("Anti-rafale : deux relevés coup sur coup ne relancent pas le second")
    void throttled_back_to_back() {
        RunnerDiag.setLevel(RunnerDiagLevel.DEBUG);
        StubConnection cdp = new StubConnection("https://teams.microsoft.com/v2/#/conversations");
        PageActions actions = new PageActions(cdp, noSleep(), sink);

        TeamsDomShapeSurvey.run(actions, noSleep());
        assertFalse(drainShape().isEmpty(), "le premier relevé produit des événements");

        TeamsDomShapeSurvey.run(actions, noSleep());
        assertTrue(RunnerDiag.isEmpty(), "le second, immédiat, est étouffé par l'anti-rafale");
    }

    // ------------------------------------------------------------------ util

    private static List<RunnerDiagEvent> allDrained() {
        return RunnerDiag.drain(10_000).events();
    }

    private List<RunnerDiagEvent> drainShape() {
        List<RunnerDiagEvent> out = new ArrayList<>();
        for (RunnerDiagEvent e : allDrained()) {
            if (TeamsDomShapeSurvey.CATEGORY.equals(e.cat())) {
                out.add(e);
            }
        }
        return out;
    }

    private static List<String> codes(List<RunnerDiagEvent> events) {
        List<String> out = new ArrayList<>();
        events.forEach(e -> out.add(e.code()));
        return out;
    }

    private static BrowserLink.Sleeper noSleep() {
        return millis -> { };
    }

    /**
     * Un navigateur de papier : il connaît son adresse (et la change à la navigation), sait cliquer, et
     * rend pour le script de forme un DOM modèle <b>porteur de noms</b> — pour prouver que rien de tout
     * cela ne franchit l'expurgation.
     */
    private static final class StubConnection implements CdpConnection {

        private final ObjectMapper mapper = new ObjectMapper();
        private final List<String> sent = new ArrayList<>();
        private String url;

        StubConnection(String url) {
            this.url = url;
        }

        @Override
        public JsonNode send(String method, ObjectNode params) {
            CdpCommands.assertAllowed(method);
            sent.add(method);
            if (CdpCommands.PAGE_NAVIGATE.equals(method)) {
                url = params.path("url").asText(url);
                return mapper.createObjectNode();
            }
            if (CdpCommands.EVALUATE.equals(method)) {
                return evaluate(params.path("expression").asText(""));
            }
            return mapper.createObjectNode();
        }

        private JsonNode evaluate(String expression) {
            ObjectNode result = mapper.createObjectNode();
            ObjectNode holder = result.putObject("result");
            if (expression.contains("location.href")) {
                holder.put("value", url);
            } else if (expression.contains("cg-domshape")) {
                holder.set("value", modelDom());
            } else if (expression.contains(".click()")) {
                holder.put("value", true);
            } else {
                holder.putNull("value");
            }
            return result;
        }

        /** Un DOM modèle : une liste de fils, chaque item portant un nom et un id — jamais reproduits. */
        private JsonNode modelDom() {
            ObjectNode dom = mapper.createObjectNode();
            dom.put("found", true);
            dom.put("truncated", false);
            com.fasterxml.jackson.databind.node.ArrayNode nodes = dom.putArray("nodes");

            ObjectNode listRoot = nodes.addObject();
            listRoot.put("d", 0);
            listRoot.put("n", 1);
            listRoot.put("tag", "div");
            listRoot.put("tid", "chat-list");
            listRoot.put("role", "tree");
            listRoot.put("text", 0);
            listRoot.put("kids", 1);
            listRoot.putArray("aria");
            listRoot.putArray("attrs");
            listRoot.putArray("cls").add("fui-Tree");

            ObjectNode item = nodes.addObject();
            item.put("d", 1);
            item.put("n", 12);
            item.put("tag", "div");
            item.put("tid", "chat-list-item-19:secret@thread.v2");
            item.put("role", "treeitem");
            // un script défaillant rendrait du texte : ce doit être ramené à une longueur
            item.put("text", "bonjour Jean Dupont");
            item.put("kids", 2);
            item.putArray("aria").add("aria-label");
            item.putArray("attrs").add("id");
            item.putArray("cls").add("fui-TreeItem");
            // clés hors schéma porteuses de valeurs sensibles → jamais lues
            item.put("title", "jean.dupont@client.fr");

            return dom;
        }

        @Override
        public void onEvent(String method, Consumer<JsonNode> listener) {
            // aucun événement dans ce stub
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
            // rien à fermer
        }
    }
}
