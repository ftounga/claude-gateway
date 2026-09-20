package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.diag.RunnerDiag;
import fr.claudegateway.runner.diag.RunnerDiagEvent;
import fr.claudegateway.runner.diag.RunnerDiagLevel;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-89 / SF-89-17 — le <b>pilote approfondi</b> du relevé de forme du DOM, gardé par le niveau F-132. Ce
 * que ce test tient : l'<b>inertie</b> hors {@code DEBUG} (rien ne part, pas même l'auto-attache), le
 * relevé du document principal <b>et des cadres iframe</b> (là où vit le chat v2), le <b>rail</b> de gauche
 * et le <b>runway</b> des messages, le cas <b>cadre inaccessible</b> ({@code frame_blocked}), et — non
 * négociable — <b>aucune valeur ne fuit</b>, y compris depuis l'iframe.
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
    @DisplayName("Inertie (CA1) : hors DEBUG, aucun script, aucune navigation, aucune auto-attache")
    void inert_when_not_debug() {
        RunnerDiag.setLevel(RunnerDiagLevel.INFO);
        StubConnection cdp = new StubConnection("https://teams.microsoft.com/v2/#/conversations");
        PageActions actions = new PageActions(cdp, noSleep(), sink);

        TeamsDomShapeSurvey.run(actions, noSleep());

        assertTrue(cdp.sent.isEmpty(), "aucune commande CDP hors DEBUG : " + cdp.sent);
        assertFalse(cdp.sent.contains(CdpCommands.SET_AUTO_ATTACH), "aucune auto-attache hors DEBUG");
        assertTrue(RunnerDiag.isEmpty(), "aucun événement de forme hors DEBUG");
    }

    @Test
    @DisplayName("Relevé (CA2/CA4/CA6/CA9) : liste, rail, runway et cadre hwc-iframe relevés, vue remise")
    void surveys_main_rail_frame_and_restores() {
        RunnerDiag.setLevel(RunnerDiagLevel.DEBUG);
        String before = "https://teams.microsoft.com/v2/#/conversations";
        StubConnection cdp = new StubConnection(before);
        PageActions actions = new PageActions(cdp, noSleep(), sink);

        TeamsDomShapeSurvey.run(actions, noSleep());

        List<RunnerDiagEvent> events = drainShape();
        List<String> codes = codes(events);
        assertTrue(codes.contains(TeamsDomShapeSurvey.LIST_CODE), "la liste est relevée");
        assertTrue(codes.contains(TeamsDomShapeSurvey.THREAD_CODE), "un fil / le runway est relevé");
        assertTrue(codes.contains(TeamsDomShapeSurvey.RAIL_CODE), "le rail de gauche est relevé (CA4)");
        // CA6 : le sous-arbre des messages est relevé, marqué message_runway.
        assertTrue(events.stream().anyMatch(e -> TeamsDomShapeSurvey.RUNWAY_AREA.equals(e.fields().get("area"))),
                "le runway des messages est marqué area=message_runway (CA6)");
        // CA2 : le cadre hwc-iframe est relevé — des événements portent frame != main.
        assertTrue(events.stream().anyMatch(e -> "hwc-iframe".equals(e.fields().get("frame"))),
                "le cadre hwc-iframe est relevé (CA2) : " + events);
        // Le document principal reste étiqueté main.
        assertTrue(events.stream().anyMatch(e -> TeamsDomShapeSurvey.MAIN.equals(e.fields().get("frame"))),
                "le document principal est étiqueté main");
        // La vue a été remise là où elle était.
        assertEquals(before, cdp.url, "la vue est remise après le relevé");
    }

    @Test
    @DisplayName("Iframe same-origin (SF-89-18/CA2/CA3) : contentDocument descendu, chat_list relevée")
    void surveys_same_origin_iframe_via_content_document() {
        RunnerDiag.setLevel(RunnerDiagLevel.DEBUG);
        StubConnection cdp = new StubConnection("https://teams.microsoft.com/v2/#/conversations");
        PageActions actions = new PageActions(cdp, noSleep(), sink);

        TeamsDomShapeSurvey.run(actions, noSleep());

        List<RunnerDiagEvent> events = drainShape();
        // CA3 : la liste des chats est relevée sous chat_list, marquée area=chat_list.
        assertTrue(events.stream().anyMatch(e -> TeamsDomShapeSurvey.CHAT_LIST_CODE.equals(e.code())
                        && TeamsDomShapeSurvey.LIST_AREA.equals(e.fields().get("area"))),
                "la liste des chats est relevée sous chat_list/area=chat_list (CA3) : " + events);
        // CA2 : elle vit dans le cadre same-origin hwc-iframe (descente par contentDocument).
        assertTrue(events.stream().anyMatch(e -> TeamsDomShapeSurvey.CHAT_LIST_CODE.equals(e.code())
                        && "hwc-iframe".equals(e.fields().get("frame"))),
                "la liste vient du cadre same-origin hwc-iframe (CA2) : " + events);
    }

    @Test
    @DisplayName("Iframe cross-origin (SF-89-18/CA4) : contentDocument nul → frame_blocked, aucune URL lue")
    void cross_origin_iframe_is_blocked_by_content_document() {
        RunnerDiag.setLevel(RunnerDiagLevel.DEBUG);
        StubConnection cdp = new StubConnection("https://teams.microsoft.com/v2/#/conversations");
        PageActions actions = new PageActions(cdp, noSleep(), sink);

        TeamsDomShapeSurvey.run(actions, noSleep());

        List<RunnerDiagEvent> events = drainShape();
        // Le cadre cross-origin de la descente same-origin est dit par frame_blocked, avec son seul label.
        assertTrue(events.stream().anyMatch(e -> TeamsDomShapeSurvey.FRAME_BLOCKED_CODE.equals(e.code())
                        && "iframe#same-origin-xo".equals(e.fields().get("frame"))),
                "un cadre same-origin cross-origin (contentDocument nul) est dit par frame_blocked (CA4)");
        // Aucune URL de cadre cross-origin ne franchit (D4) : le motif d'hôte n'est pas émis pour ce cadre.
        assertTrue(events.stream().filter(e -> "iframe#same-origin-xo".equals(e.fields().get("frame")))
                        .noneMatch(e -> e.fields().containsKey("host")),
                "aucune URL/host du cadre cross-origin n'est lue (D4)");
    }

    @Test
    @DisplayName("Cadre inaccessible (CA3) : un cadre attaché mais inévaluable est dit par frame_blocked")
    void inaccessible_frame_is_reported_not_crashed() {
        RunnerDiag.setLevel(RunnerDiagLevel.DEBUG);
        StubConnection cdp = new StubConnection("https://teams.microsoft.com/v2/#/conversations");
        PageActions actions = new PageActions(cdp, noSleep(), sink);

        TeamsDomShapeSurvey.run(actions, noSleep());

        List<RunnerDiagEvent> events = drainShape();
        assertTrue(codes(events).contains(TeamsDomShapeSurvey.FRAME_BLOCKED_CODE),
                "le cadre inaccessible est dit par frame_blocked (CA3)");
        // Le relevé continue malgré le cadre bloqué : le cadre accessible est bien relevé.
        assertTrue(events.stream().anyMatch(e -> "hwc-iframe".equals(e.fields().get("frame"))),
                "un cadre bloqué n'empêche pas les autres d'être relevés");
    }

    @Test
    @DisplayName("Cadre hors domaine : un iframe non-Microsoft n'est ni relevé ni bloqué (garde F-108)")
    void off_domain_frame_is_ignored() {
        RunnerDiag.setLevel(RunnerDiagLevel.DEBUG);
        StubConnection cdp = new StubConnection("https://teams.microsoft.com/v2/#/conversations");
        PageActions actions = new PageActions(cdp, noSleep(), sink);

        TeamsDomShapeSurvey.run(actions, noSleep());

        // Le cadre evil.example.com ne doit jamais apparaître : ni relevé, ni frame_blocked.
        String all = drainShape().stream().map(e -> e.msg() + " " + e.fields())
                .reduce("", (a, b) -> a + " || " + b);
        assertFalse(all.contains("example.com"), "un cadre hors domaine ne remonte jamais : " + all);
    }

    @Test
    @DisplayName("Vie privée bout-en-bout (CA5/CA7) : aucun nom/message/id ne fuit — document, iframe ET sidebar")
    void no_value_leaks_end_to_end_including_iframe() {
        RunnerDiag.setLevel(RunnerDiagLevel.DEBUG);
        StubConnection cdp = new StubConnection("https://teams.microsoft.com/v2/#/conversations");
        PageActions actions = new PageActions(cdp, noSleep(), sink);

        TeamsDomShapeSurvey.run(actions, noSleep());

        String all = drainShape().stream()
                .map(e -> e.msg() + " " + e.fields())
                .reduce("", (a, b) -> a + " || " + b);
        // Valeurs semées dans le document principal, dans l'iframe same-origin ET dans la zone --sidebar (SF-89-19).
        for (String forbidden : List.of("Jean Dupont", "bonjour", "Paul", "secret", "thread.v2",
                "jean.dupont@client.fr", "Marie Martin", "demain")) {
            assertFalse(all.contains(forbidden), "une valeur a fui : « " + forbidden + " » — " + all);
        }
    }

    @Test
    @DisplayName("Zones de layout (SF-89-19/CA2) : chaque app-layout-area-- est relevée sous layout_area")
    void surveys_all_layout_areas() {
        RunnerDiag.setLevel(RunnerDiagLevel.DEBUG);
        StubConnection cdp = new StubConnection("https://teams.microsoft.com/v2/#/conversations");
        PageActions actions = new PageActions(cdp, noSleep(), sink);

        TeamsDomShapeSurvey.run(actions, noSleep());

        List<RunnerDiagEvent> events = drainShape();
        // CA2 : --main ET --sidebar sont relevées sous layout_area, étiquetées frame=main.
        assertTrue(events.stream().anyMatch(e -> TeamsDomShapeSurvey.LAYOUT_CODE.equals(e.code())
                        && "app-layout-area--main".equals(e.fields().get("area"))
                        && TeamsDomShapeSurvey.MAIN.equals(e.fields().get("frame"))),
                "la zone --main est relevée sous layout_area (CA2) : " + events);
        assertTrue(events.stream().anyMatch(e -> TeamsDomShapeSurvey.LAYOUT_CODE.equals(e.code())
                        && "app-layout-area--sidebar".equals(e.fields().get("area"))
                        && TeamsDomShapeSurvey.MAIN.equals(e.fields().get("frame"))),
                "la zone --sidebar est relevée sous layout_area (CA2) : " + events);
    }

    @Test
    @DisplayName("Liste dans la sidebar (SF-89-19/CA3) : chat_list émis avec area=app-layout-area--sidebar")
    void surveys_chat_list_in_sidebar_layout_area() {
        RunnerDiag.setLevel(RunnerDiagLevel.DEBUG);
        StubConnection cdp = new StubConnection("https://teams.microsoft.com/v2/#/conversations");
        PageActions actions = new PageActions(cdp, noSleep(), sink);

        TeamsDomShapeSurvey.run(actions, noSleep());

        List<RunnerDiagEvent> events = drainShape();
        // CA3 : la liste des chats vit dans la zone --sidebar du document principal.
        assertTrue(events.stream().anyMatch(e -> TeamsDomShapeSurvey.CHAT_LIST_CODE.equals(e.code())
                        && "app-layout-area--sidebar".equals(e.fields().get("area"))
                        && TeamsDomShapeSurvey.MAIN.equals(e.fields().get("frame"))),
                "la liste des chats est relevée sous chat_list depuis la sidebar (CA3) : " + events);
        // La zone --main n'a pas de liste : aucun chat_list ne doit porter area=app-layout-area--main.
        assertFalse(events.stream().anyMatch(e -> TeamsDomShapeSurvey.CHAT_LIST_CODE.equals(e.code())
                        && "app-layout-area--main".equals(e.fields().get("area"))),
                "aucune liste n'est inventée pour une zone qui n'en porte pas : " + events);
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
     * Un navigateur de papier <b>avec des cadres</b> (SF-89-17) : il connaît son adresse, sait cliquer, rend
     * un DOM modèle pour le document principal <b>et</b> pour chaque cadre attaché — tous porteurs de noms,
     * pour prouver que rien ne franchit l'expurgation. Trois cadres iframe sont annoncés à l'auto-attache :
     * {@code F1} accessible (le vrai chat, dans hwc-iframe), {@code F2} attaché mais inévaluable
     * (→ {@code frame_blocked}), et {@code F3} hors domaine Microsoft (→ ignoré par la garde).
     */
    private static final class StubConnection implements CdpConnection {

        private final ObjectMapper mapper = new ObjectMapper();
        private final List<String> sent = new ArrayList<>();
        private final Map<String, List<BiConsumer<String, JsonNode>>> sessionListeners = new LinkedHashMap<>();
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
                return evaluate(params.path("expression").asText(""), false);
            }
            if (CdpCommands.SET_AUTO_ATTACH.equals(method)) {
                fireAttached();
                return mapper.createObjectNode();
            }
            return mapper.createObjectNode();
        }

        @Override
        public JsonNode send(String sessionId, String method, ObjectNode params) {
            if (sessionId == null || sessionId.isBlank()) {
                return send(method, params);
            }
            CdpCommands.assertAllowed(method);
            sent.add(sessionId + ':' + method);
            if ("F2".equals(sessionId)) {
                // Cadre attaché mais inévaluable : détaché / cross-origin non attachable.
                throw new BrowserLinkException(BrowserLinkException.COMMAND_REFUSED, "cadre inaccessible");
            }
            if (CdpCommands.EVALUATE.equals(method)) {
                return evaluate(params.path("expression").asText(""), true);
            }
            return mapper.createObjectNode();
        }

        /** Annonce les cadres attachés (comme le ferait {@code Target.attachedToTarget} après setAutoAttach). */
        private void fireAttached() {
            attach("F1", "iframe", "https://teams.microsoft.com/hwc");
            attach("F2", "iframe", "https://teams.microsoft.com/other-frame");
            attach("F3", "iframe", "https://evil.example.com/embed");
        }

        private void attach(String sessionId, String type, String targetUrl) {
            ObjectNode params = mapper.createObjectNode();
            params.put("sessionId", sessionId);
            ObjectNode info = params.putObject("targetInfo");
            info.put("type", type);
            info.put("url", targetUrl);
            sessionListeners.getOrDefault("Target.attachedToTarget", List.of())
                    .forEach(l -> l.accept("", params));
        }

        private JsonNode evaluate(String expression, boolean inFrame) {
            ObjectNode result = mapper.createObjectNode();
            ObjectNode holder = result.putObject("result");
            if (expression.contains("location.href")) {
                holder.put("value", url);
            } else if (expression.contains("cg-domareas")) {
                // SF-89-19 : l'énumération de toutes les zones de layout du document principal.
                holder.set("value", layoutAreasDom());
            } else if (expression.contains("cg-domframes")) {
                // SF-89-18 : la descente same-origin par contentDocument, exécutée dans l'onglet.
                holder.set("value", sameOriginFramesDom());
            } else if (expression.contains("cg-domshape")) {
                holder.set("value", inFrame ? frameDom() : mainDom());
            } else if (expression.contains(".click()")) {
                holder.put("value", true);
            } else {
                holder.putNull("value");
            }
            return result;
        }

        /** La coquille du document principal : le volet main, une iframe hwc, et un item porteur de nom. */
        private JsonNode mainDom() {
            ObjectNode dom = mapper.createObjectNode();
            dom.put("found", true);
            dom.put("truncated", false);
            ArrayNode nodes = dom.putArray("nodes");

            ObjectNode main = nodes.addObject();
            fill(main, 0, 1, "div", "app-layout-area--main", "main", 0, 2);

            ObjectNode iframe = nodes.addObject();
            fill(iframe, 1, 1, "iframe", "hwc-iframe", "", 0, 0);

            ObjectNode item = nodes.addObject();
            fill(item, 1, 12, "div", "chat-list-item-19:secret@thread.v2", "treeitem", 0, 2);
            // un script défaillant rendrait du texte : ce doit être ramené à une longueur
            item.put("text", "bonjour Jean Dupont");
            item.putArray("aria").add("aria-label");
            item.putArray("attrs").add("id");
            item.put("title", "jean.dupont@client.fr");
            return dom;
        }

        /** Le vrai chat, DANS l'iframe : le runway et un message porteur de nom, message, adresse, id. */
        private JsonNode frameDom() {
            ObjectNode dom = mapper.createObjectNode();
            dom.put("found", true);
            dom.put("truncated", false);
            ArrayNode nodes = dom.putArray("nodes");

            ObjectNode runway = nodes.addObject();
            fill(runway, 0, 1, "div", "message-pane-list-runway", "list", 0, 20);

            ObjectNode message = nodes.addObject();
            fill(message, 1, 20, "div", "message-19:secret@thread.v2", "listitem", 0, 3);
            // Toutes ces valeurs doivent être expurgées, même venant de l'iframe.
            message.put("text", "bonjour Paul, on se voit demain ?");
            message.putArray("aria").add("aria-label");
            message.putArray("attrs").add("id");
            message.put("title", "Marie Martin");
            message.put("href", "https://teams.microsoft.com/x/19:secret@thread.v2");
            return dom;
        }

        /**
         * SF-89-18 : ce que la descente same-origin (script « cg-domframes », dans l'onglet) rend — un
         * cadre same-origin lisible (hwc-iframe, contentDocument non nul) portant la <b>liste des chats</b>
         * seedée de valeurs sensibles, et un cadre <b>cross-origin</b> (contentDocument nul → blocked).
         */
        private JsonNode sameOriginFramesDom() {
            ObjectNode dom = mapper.createObjectNode();
            ArrayNode frames = dom.putArray("frames");

            ObjectNode hwc = frames.addObject();
            hwc.put("label", "hwc-iframe");
            hwc.put("blocked", false);
            emptyShape(hwc, "root");
            // La liste des chats, DANS le contentDocument same-origin : un arbre + un item porteur de nom.
            ObjectNode list = hwc.putObject("list");
            list.put("found", true);
            list.put("truncated", false);
            ArrayNode listNodes = list.putArray("nodes");
            ObjectNode tree = listNodes.addObject();
            fill(tree, 0, 1, "div", "chat-list", "tree", 0, 12);
            ObjectNode item = listNodes.addObject();
            fill(item, 1, 12, "div", "chat-list-item-19:secret@thread.v2", "treeitem", 0, 2);
            // Toutes ces valeurs doivent être expurgées, même venant du contentDocument same-origin.
            item.put("text", "bonjour Paul, on se voit demain ?");
            item.putArray("aria").add("aria-label");
            item.putArray("attrs").add("id");
            item.put("title", "Marie Martin");
            item.put("href", "https://teams.microsoft.com/x/19:secret@thread.v2");
            emptyShape(hwc, "rail");
            emptyShape(hwc, "runway");

            // Un cadre réellement cross-origin : contentDocument nul → blocked, aucune URL lue. Label
            // distinct du chemin CDP (SF-89-17), pour prouver que la voie same-origin n'émet aucun host.
            ObjectNode blocked = frames.addObject();
            blocked.put("label", "iframe#same-origin-xo");
            blocked.put("blocked", true);
            return dom;
        }

        /**
         * SF-89-19 : ce que l'énumération des zones de layout (script « cg-domareas », dans l'onglet) rend —
         * plusieurs {@code app-layout-area--*}, dont {@code --main} (sans liste) et {@code --sidebar} portant
         * la <b>liste des chats</b> (arbre + item) seedée de valeurs sensibles, pour prouver que rien ne
         * franchit l'expurgation, zone sidebar incluse.
         */
        private JsonNode layoutAreasDom() {
            ObjectNode dom = mapper.createObjectNode();
            ArrayNode areas = dom.putArray("areas");

            // La zone --main : sa forme, mais pas de liste des conversations (c'est le volet du chat ouvert).
            ObjectNode main = areas.addObject();
            main.put("area", "app-layout-area--main");
            ObjectNode mainShape = main.putObject("shape");
            mainShape.put("found", true);
            mainShape.put("truncated", false);
            ArrayNode mainNodes = mainShape.putArray("nodes");
            ObjectNode mainRoot = mainNodes.addObject();
            fill(mainRoot, 0, 1, "div", "app-layout-area--main", "main", 0, 1);
            emptyShape(main, "list");

            // La zone --sidebar : LÀ vit la liste des conversations, seedée de valeurs sensibles.
            ObjectNode sidebar = areas.addObject();
            sidebar.put("area", "app-layout-area--sidebar");
            ObjectNode sideShape = sidebar.putObject("shape");
            sideShape.put("found", true);
            sideShape.put("truncated", false);
            ArrayNode sideNodes = sideShape.putArray("nodes");
            ObjectNode sideRoot = sideNodes.addObject();
            fill(sideRoot, 0, 1, "div", "app-layout-area--sidebar", "complementary", 0, 1);
            ObjectNode list = sidebar.putObject("list");
            list.put("found", true);
            list.put("truncated", false);
            ArrayNode listNodes = list.putArray("nodes");
            ObjectNode tree = listNodes.addObject();
            fill(tree, 0, 1, "div", "chat-list", "tree", 0, 12);
            ObjectNode item = listNodes.addObject();
            fill(item, 1, 12, "div", "chat-list-item-19:secret@thread.v2", "treeitem", 0, 2);
            // Toutes ces valeurs doivent être expurgées, même venant de la zone sidebar.
            item.put("text", "bonjour Paul, on se voit demain ?");
            item.putArray("aria").add("aria-label");
            item.putArray("attrs").add("id");
            item.put("title", "Marie Martin");
            item.put("href", "https://teams.microsoft.com/x/19:secret@thread.v2");
            return dom;
        }

        /** Une squelette vide sous une clé de zone d'un cadre (SF-89-18). */
        private void emptyShape(ObjectNode frame, String key) {
            ObjectNode survey = frame.putObject(key);
            survey.put("found", false);
            survey.put("truncated", false);
            survey.putArray("nodes");
        }

        private static void fill(ObjectNode n, int depth, int repeat, String tag, String tid, String role,
                int textLen, int kids) {
            n.put("d", depth);
            n.put("n", repeat);
            n.put("tag", tag);
            n.put("tid", tid);
            n.put("role", role);
            n.put("text", textLen);
            n.put("kids", kids);
            n.putArray("aria");
            n.putArray("attrs");
            n.putArray("cls");
        }

        @Override
        public void onEvent(String method, Consumer<JsonNode> listener) {
            onSessionEvent(method, (sessionId, params) -> listener.accept(params));
        }

        @Override
        public void onSessionEvent(String method, BiConsumer<String, JsonNode> listener) {
            sessionListeners.computeIfAbsent(method, k -> new ArrayList<>()).add(listener);
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
