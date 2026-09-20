package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-89 / SF-89-16 — <b>la forme du DOM, jamais son contenu</b>. Le DOM v2 n'existe pas en CI (le PO
 * lancera le relevé réel) : ces tests tiennent la <b>logique d'expurgation</b> et l'assemblage sur des
 * squelettes modèles — dont le point non négociable : aucune valeur, aucun texte, aucun nom ne survit.
 */
class TeamsDomShapeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    // ------------------------------------------------------------------ cas nominal

    @Test
    @DisplayName("Nominal : tag, data-tid, role, profondeur, compteur de frères et enfants sont portés")
    void nominal_shape_carries_structure() {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("found", true);
        raw.put("truncated", false);
        ArrayNode nodes = raw.putArray("nodes");
        nodes.add(node(0, 1, "div", "chat-list", "tree", 12,
                List.of(), List.of("data-tid"), List.of("fui-Tree"), 0));
        // 12 frères identiques, repliés côté script en un nœud représentatif n=12
        nodes.add(node(1, 12, "div", "chat-list-item", "treeitem", 3,
                List.of("aria-selected"), List.of("id", "data-item-id"), List.of("fui-TreeItem"), 0));

        TeamsDomShape.Survey survey = TeamsDomShape.refilter(raw);

        assertTrue(survey.found());
        assertEquals(2, survey.nodes().size());
        TeamsDomShape.Node list = survey.nodes().get(0);
        assertEquals("div", list.tag());
        assertEquals("chat-list", list.tid());
        assertEquals("tree", list.role());
        TeamsDomShape.Node item = survey.nodes().get(1);
        assertEquals(12, item.repeat());
        assertEquals("chat-list-item", item.tid());
        assertEquals(1, item.depth());
        assertTrue(item.aria().contains("aria-selected"));
        // La ligne lisible porte la forme, et le compteur de frères.
        assertTrue(TeamsDomShape.line(item).contains("[data-tid=chat-list-item]"), TeamsDomShape.line(item));
        assertTrue(TeamsDomShape.line(item).contains("x12"), TeamsDomShape.line(item));
    }

    // ------------------------------------------------------------------ VIE PRIVÉE (CA4/CA5) — non négociable

    @Test
    @DisplayName("Vie privée : aucun texte, aucun nom, aucune adresse, aucun id de fil ne survit")
    void privacy_no_value_survives() {
        ObjectNode raw = mapper.createObjectNode();
        raw.put("found", true);
        ArrayNode nodes = raw.putArray("nodes");

        ObjectNode leak = mapper.createObjectNode();
        leak.put("d", 2);
        leak.put("n", 1);
        leak.put("tag", "span");
        // un data-tid qui EMBARQUE un id de fil → doit être ramené à {id}
        leak.put("tid", "chat-list-item-19:secret@thread.v2");
        leak.put("role", "treeitem");
        // un script qui aurait mal fait son travail : du texte en clair au lieu d'une longueur
        leak.put("text", "bonjour Paul");
        leak.put("kids", 0);
        // aria/attrs : une clé légitime, et une pseudo-clé qui porte une VALEUR (nom) → rejetée
        ArrayNode aria = leak.putArray("aria");
        aria.add("aria-label");
        aria.add("aria-label=Message de Jean Dupont");
        ArrayNode attrs = leak.putArray("attrs");
        attrs.add("title");
        attrs.add("href");
        attrs.add("id");
        // des jetons de classe, dont un qui porte une adresse → assaini
        ArrayNode cls = leak.putArray("cls");
        cls.add("fui-Primitive");
        // des clés hors schéma, portant des valeurs sensibles → jamais lues
        leak.put("title", "jean.dupont@client.fr");
        leak.put("href", "https://teams.microsoft.com/x/19:secret@thread.v2");
        leak.put("textContent", "bonjour Paul, on se voit demain ?");
        nodes.add(leak);

        TeamsDomShape.Survey survey = TeamsDomShape.refilter(raw);

        TeamsDomShape.Node node = survey.nodes().get(0);
        String rendered = TeamsDomShape.line(node) + " || " + TeamsDomShape.fields(node, 1);
        for (String forbidden : List.of("Jean Dupont", "bonjour", "Paul", "jean.dupont@client.fr",
                "secret", "thread.v2", "demain", "Message de Jean")) {
            assertFalse(rendered.contains(forbidden),
                    "une valeur a fui dans la squelette : « " + forbidden + " » — " + rendered);
        }
        // Le texte est devenu une longueur (0, car le script modèle a rendu du texte, pas un entier).
        assertEquals(0, node.textLen());
        // Le data-tid porteur d'id est assaini.
        assertEquals(SurveyPaths.ID, node.tid());
        // Seule la clé aria légitime survit ; celle qui portait une valeur est écartée.
        assertEquals(List.of("aria-label"), node.aria());
        assertTrue(node.attrs().contains("title") && node.attrs().contains("href"));
    }

    // ------------------------------------------------------------------ assainissement & bornes (CA6/CA7)

    @Test
    @DisplayName("Assainissement : GUID/hex en data-tid ou classe ramenés à {id} ; profondeur bornée")
    void scrubbing_and_bounds() {
        ObjectNode raw = mapper.createObjectNode();
        ArrayNode nodes = raw.putArray("nodes");
        nodes.add(node(25, 1, "div", "12345678-1234-1234-1234-123456789012", "listitem", 0,
                List.of("aria-hidden", "x=y", "bad name"), List.of(), List.of("__abcdef0123456789hex"), 0));

        TeamsDomShape.Node node = TeamsDomShape.refilter(raw).nodes().get(0);

        assertEquals(SurveyPaths.ID, node.tid(), "un GUID en data-tid doit devenir {id}");
        assertEquals(TeamsDomShape.MAX_DEPTH, node.depth(), "la profondeur est bornée à MAX_DEPTH");
        assertEquals(List.of("aria-hidden"), node.aria(), "seules les vraies clés survivent");
        assertTrue(node.classes().contains(SurveyPaths.ID), "un jeton de classe hex long est assaini");
    }

    // ------------------------------------------------------------------ SF-89-17 : profondeur accrue

    @Test
    @DisplayName("Profondeur accrue (CA5) : le cap monte à 22 ; un nœud à 21 est gardé, à 23 borné")
    void deeper_capture_reaches_message_nodes() {
        assertEquals(22, TeamsDomShape.MAX_DEPTH, "SF-89-17 : le cap de profondeur monte de 12 à 22");

        ObjectNode raw = mapper.createObjectNode();
        ArrayNode nodes = raw.putArray("nodes");
        // un nœud auteur à profondeur 21 (hors de portée du cap 12 de SF-89-16)
        nodes.add(node(21, 1, "div", "message-body", "listitem", 0, List.of(), List.of(), List.of(), 0));
        // un nœud plus profond que le cap → borné à 22
        nodes.add(node(23, 1, "span", "author", "", 0, List.of(), List.of(), List.of(), 0));

        List<TeamsDomShape.Node> out = TeamsDomShape.refilter(raw).nodes();

        assertEquals(21, out.get(0).depth(), "un nœud à 21 est capturé (impossible avec le cap 12)");
        assertEquals(22, out.get(1).depth(), "un nœud plus profond que le cap est ramené à 22");
    }

    // ------------------------------------------------------------------ SF-89-17 : labels de cadre

    @Test
    @DisplayName("Labels de cadre (SF-89-17) : les tids des nœuds iframe sortent, dans l'ordre, assainis")
    void iframe_tids_are_collected_in_order() {
        ObjectNode raw = mapper.createObjectNode();
        ArrayNode nodes = raw.putArray("nodes");
        nodes.add(node(0, 1, "div", "app-layout-area--main", "main", 0,
                List.of(), List.of(), List.of(), 0));
        nodes.add(node(1, 1, "iframe", "hwc-iframe", "", 0, List.of(), List.of(), List.of(), 0));
        // une seconde iframe dont le tid porte un id → assaini en {id}, mais toujours listé dans l'ordre
        nodes.add(node(1, 1, "iframe", "frame-19:secret@thread.v2", "", 0,
                List.of(), List.of(), List.of(), 0));

        List<String> tids = TeamsDomShape.iframeTids(TeamsDomShape.refilter(raw));

        assertEquals(List.of("hwc-iframe", SurveyPaths.ID), tids,
                "les iframes sortent dans l'ordre, tid assaini");
    }

    // ------------------------------------------------------------------ SF-89-17 : racines rail/runway

    @Test
    @DisplayName("Rail/runway (SF-89-17) : racines ciblées par data-tid/role, script sans interdits")
    void rail_and_runway_roots_target_data_tid_not_class() {
        // Les racines ne ciblent que des data-tid / role, jamais une classe (les classes v2 sont hashées).
        for (String selector : TeamsDomShape.RAIL_SELECTORS) {
            assertTrue(selector.contains("data-tid") || selector.contains("role"),
                    "le rail se cible par data-tid/role, pas par classe : " + selector);
        }
        for (String selector : TeamsDomShape.RUNWAY_SELECTORS) {
            assertTrue(selector.contains("data-tid"),
                    "le runway se cible par data-tid : " + selector);
        }
        // Le script des nouvelles racines garde les mêmes interdits que la racine large.
        for (List<String> roots : List.of(TeamsDomShape.RAIL_SELECTORS, TeamsDomShape.RUNWAY_SELECTORS)) {
            String js = TeamsDomShape.surveyScript(mapper, roots);
            assertFalse(js.contains("textContent"));
            assertFalse(js.contains("innerText"));
            assertFalse(js.toLowerCase(java.util.Locale.ROOT).contains("cookie"));
            assertFalse(js.contains("localStorage"));
        }
    }

    // ------------------------------------------------------------------ SF-89-18 : iframe same-origin

    @Test
    @DisplayName("Liste des chats (SF-89-18) : LIST_SELECTORS ciblent data-tid/role, jamais une classe")
    void chat_list_roots_target_data_tid_or_role_not_class() {
        for (String selector : TeamsDomShape.LIST_SELECTORS) {
            assertTrue(selector.contains("data-tid") || selector.contains("role"),
                    "la liste des chats se cible par data-tid/role, pas par classe : " + selector);
        }
    }

    @Test
    @DisplayName("Script iframe (SF-89-18/CA7) : descend via contentDocument, sans textContent/cookie/stockage")
    void frames_script_descends_content_document_without_forbidden_reads() {
        String js = TeamsDomShape.framesScript(mapper);

        // Le cœur de la voie retenue : on lit contentDocument, dans l'onglet.
        assertTrue(js.contains("contentDocument"), "le script descend via iframe.contentDocument");
        assertTrue(js.contains("querySelectorAll('iframe')"), "le script parcourt les <iframe>");
        // Mêmes interdits que la racine large : jamais un contenu, jamais un cookie, jamais le stockage.
        assertFalse(js.contains("textContent"), "le script ne lit jamais textContent");
        assertFalse(js.contains("innerText"), "le script ne lit jamais innerText");
        assertFalse(js.toLowerCase(java.util.Locale.ROOT).contains("cookie"));
        assertFalse(js.contains("localStorage"));
        assertFalse(js.contains("sessionStorage"));
        // Il ne mesure QUE des longueurs de nœuds texte, et cible les data-tid.
        assertTrue(js.contains("nodeValue"));
        assertTrue(js.contains(".length"));
        assertTrue(js.contains("data-tid"));
        // Et il ne lit jamais l'adresse d'un cadre cross-origin (aucune URL du cadre ne franchit — D4).
        assertFalse(js.contains(".src"), "le script ne lit jamais le src d'une iframe cross-origin");
    }

    @Test
    @DisplayName("refilterFrames (SF-89-18) : label assaini, borne MAX_FRAMES, cross-origin marqué blocked")
    void refilter_frames_sanitizes_and_bounds() {
        ObjectNode raw = mapper.createObjectNode();
        ArrayNode frames = raw.putArray("frames");
        // Un cadre same-origin lisible : sa liste porte un item (avec un id de fil dans le data-tid).
        ObjectNode ok = frames.addObject();
        ok.put("label", "hwc-iframe");
        ok.put("blocked", false);
        emptySurvey(ok, "root");
        ObjectNode list = ok.putObject("list");
        list.put("found", true);
        list.put("truncated", false);
        ArrayNode listNodes = list.putArray("nodes");
        listNodes.add(node(0, 1, "div", "chat-list", "tree", 0, List.of(), List.of(), List.of(), 0));
        listNodes.add(node(1, 12, "div", "chat-list-item-19:secret@thread.v2", "treeitem", 0,
                List.of(), List.of(), List.of(), 0));
        emptySurvey(ok, "rail");
        emptySurvey(ok, "runway");
        // Un cadre cross-origin : blocked, dont le label porte un id → assaini.
        ObjectNode blocked = frames.addObject();
        blocked.put("label", "frame-19:secret@thread.v2");
        blocked.put("blocked", true);
        // Deux cadres de plus que la borne : ignorés.
        for (int i = 0; i < TeamsDomShape.MAX_FRAMES; i++) {
            ObjectNode extra = frames.addObject();
            extra.put("label", "iframe#extra" + i);
            extra.put("blocked", true);
        }

        List<TeamsDomShape.FrameShape> out = TeamsDomShape.refilterFrames(raw);

        assertEquals(TeamsDomShape.MAX_FRAMES, out.size(), "le nombre de cadres est borné à MAX_FRAMES");
        TeamsDomShape.FrameShape first = out.get(0);
        assertEquals("hwc-iframe", first.label());
        assertFalse(first.blocked());
        assertTrue(first.list().found(), "la liste du cadre same-origin est relevée");
        // L'id de fil embarqué dans le data-tid de l'item est assaini, iframe incluse.
        assertEquals(SurveyPaths.ID, first.list().nodes().get(1).tid());
        TeamsDomShape.FrameShape second = out.get(1);
        assertTrue(second.blocked(), "le cadre cross-origin est marqué blocked");
        assertEquals(SurveyPaths.ID, second.label(), "un label porteur d'id est assaini");
    }

    @Test
    @DisplayName("Bornes : au-delà de MAX_NODES, la squelette est coupée et le dit")
    void node_count_is_bounded() {
        ObjectNode raw = mapper.createObjectNode();
        ArrayNode nodes = raw.putArray("nodes");
        for (int i = 0; i < TeamsDomShape.MAX_NODES + 40; i++) {
            nodes.add(node(1, 1, "div", "item", "treeitem", 0, List.of(), List.of(), List.of(), 0));
        }

        TeamsDomShape.Survey survey = TeamsDomShape.refilter(raw);

        assertEquals(TeamsDomShape.MAX_NODES, survey.nodes().size());
        assertTrue(survey.truncated(), "un dépassement du plafond de nœuds est dit");
    }

    // ------------------------------------------------------------------ garde du script

    @Test
    @DisplayName("Garde script : jamais textContent/innerText comme valeur, ni cookie ni stockage")
    void script_never_reads_content() {
        String js = TeamsDomShape.surveyScript(mapper, TeamsDomShape.ROOT_SELECTORS);

        assertFalse(js.contains("textContent"), "le script ne lit jamais textContent");
        assertFalse(js.contains("innerText"), "le script ne lit jamais innerText");
        assertFalse(js.toLowerCase(java.util.Locale.ROOT).contains("cookie"));
        assertFalse(js.contains("localStorage"));
        assertFalse(js.contains("sessionStorage"));
        // Il ne mesure QUE des longueurs de nœuds texte, et replie les frères par signature.
        assertTrue(js.contains("nodeValue"));
        assertTrue(js.contains(".length"));
        assertTrue(js.contains("data-tid"));
    }

    // ------------------------------------------------------------------ util

    /** Pose une squelette vide (found=false) sous la clé donnée d'un cadre — zone absente. */
    private void emptySurvey(ObjectNode frame, String key) {
        ObjectNode survey = frame.putObject(key);
        survey.put("found", false);
        survey.put("truncated", false);
        survey.putArray("nodes");
    }

    private ObjectNode node(int depth, int repeat, String tag, String tid, String role, int kids,
            List<String> aria, List<String> attrs, List<String> classes, int textLen) {
        ObjectNode n = mapper.createObjectNode();
        n.put("d", depth);
        n.put("n", repeat);
        n.put("tag", tag);
        n.put("tid", tid);
        n.put("role", role);
        n.put("kids", kids);
        n.put("text", textLen);
        ArrayNode a = n.putArray("aria");
        aria.forEach(a::add);
        ArrayNode b = n.putArray("attrs");
        attrs.forEach(b::add);
        ArrayNode c = n.putArray("cls");
        classes.forEach(c::add);
        return n;
    }
}
