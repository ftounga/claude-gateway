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
        nodes.add(node(20, 1, "div", "12345678-1234-1234-1234-123456789012", "listitem", 0,
                List.of("aria-hidden", "x=y", "bad name"), List.of(), List.of("__abcdef0123456789hex"), 0));

        TeamsDomShape.Node node = TeamsDomShape.refilter(raw).nodes().get(0);

        assertEquals(SurveyPaths.ID, node.tid(), "un GUID en data-tid doit devenir {id}");
        assertEquals(TeamsDomShape.MAX_DEPTH, node.depth(), "la profondeur est bornée");
        assertEquals(List.of("aria-hidden"), node.aria(), "seules les vraies clés survivent");
        assertTrue(node.classes().contains(SurveyPaths.ID), "un jeton de classe hex long est assaini");
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
