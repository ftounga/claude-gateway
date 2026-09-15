package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * F-89 / SF-89-12 — <b>le squelette d'un corps</b> : les NOMS de champs et leur TYPE, jamais une
 * valeur. Le test central est celui de la vie privée : un corps porteur de valeurs sensibles ne doit
 * en laisser <b>aucune</b> dans la sortie.
 */
class PayloadShapeTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private JsonNode json(String raw) {
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DisplayName("Un objet : ses noms de champs et le type de chacun")
    void objectFieldsAndTypes() {
        String shape = PayloadShape.of(json(
                "{\"id\":\"19:x\",\"subject\":\"Point projet\",\"count\":3,\"recorded\":true,\"link\":null}"));
        assertEquals("{ id: string, subject: string, count: number, recorded: boolean, link: null }", shape);
    }

    @Test
    @DisplayName("Un tableau : le type des éléments et le squelette du PREMIER élément seulement")
    void arrayExposesEnvelopeAndFirstItem() {
        String shape = PayloadShape.of(json(
                "{\"value\":[{\"id\":\"a\",\"subject\":\"r1\"},{\"id\":\"b\",\"subject\":\"r2\"}],"
                        + "\"@odata.count\":2}"));
        assertEquals("{ value: array<object>[ { id: string, subject: string } ], @odata.count: number }",
                shape);
    }

    @Test
    @DisplayName("VIE PRIVÉE (non négociable) : aucune valeur de feuille ne fuit — seulement noms + types")
    void noLeafValueEverLeaks() {
        String body = "{"
                + "\"token\":\"Bearer SECRET-JETON-DE-SESSION\","
                + "\"value\":[{"
                + "  \"id\":\"19:secretthread@thread.v2\","
                + "  \"from\":{\"displayName\":\"Jean Dupont\",\"email\":\"jean.dupont@client-cagip.fr\"},"
                + "  \"content\":\"Rendez-vous confidentiel lundi à 14h, dossier 4815162342\","
                + "  \"amount\":987654321,"
                + "  \"deleted\":false}]}";
        String shape = PayloadShape.of(json(body));

        // Les noms de champs SONT là (c'est le but) ...
        for (String name : List.of("token", "value", "id", "from", "displayName", "email", "content",
                "amount", "deleted")) {
            assertTrue(shape.contains(name), "le nom de champ « " + name + " » doit figurer : " + shape);
        }
        // ... mais AUCUNE valeur, même partielle.
        for (String secret : List.of("Bearer", "SECRET", "JETON", "19:secretthread", "thread.v2",
                "Jean", "Dupont", "jean.dupont", "client-cagip", "@client", "Rendez-vous",
                "confidentiel", "lundi", "dossier", "4815162342", "987654321", "false")) {
            assertFalse(shape.contains(secret), "fuite « " + secret + " » dans : " + shape);
        }
        // Les feuilles sont réduites à leur type.
        assertTrue(shape.contains("content: string"), shape);
        assertTrue(shape.contains("amount: number"), shape);
        assertTrue(shape.contains("deleted: boolean"), shape);
    }

    @Test
    @DisplayName("Profondeur bornée : au-delà de la limite, le type sans dépliage")
    void depthIsBounded() {
        String deep = "{\"a\":{\"b\":{\"c\":{\"d\":{\"e\":\"secret\"}}}}}";
        String shape = PayloadShape.of(json(deep), 3);
        assertFalse(shape.contains("secret"), shape);
        assertFalse(shape.contains("e:"), "le 5e niveau n'est pas déplié : " + shape);
        // { a: { b: { c: object } } } — c s'arrête au type, sans montrer d.
        assertTrue(shape.contains("c: object"), shape);
    }

    @Test
    @DisplayName("Un tableau de N éléments ne déplie que le premier")
    void onlyFirstArrayElementIsDescribed() {
        String shape = PayloadShape.of(json(
                "{\"items\":[{\"a\":1},{\"b\":\"VALEUR-DU-DEUXIEME\"},{\"c\":true}]}"));
        assertFalse(shape.contains("VALEUR-DU-DEUXIEME"), shape);
        assertFalse(shape.contains("b:"), "le 2e élément n'est pas décrit : " + shape);
        assertEquals("{ items: array<object>[ { a: number } ] }", shape);
    }

    @Test
    @DisplayName("Nombre de clés borné par niveau ; le reste est compté, pas écrit")
    void keysPerLevelAreBounded() {
        StringBuilder big = new StringBuilder("{");
        int total = PayloadShape.MAX_KEYS_PER_LEVEL + 25;
        for (int i = 0; i < total; i++) {
            if (i > 0) {
                big.append(',');
            }
            big.append("\"champ").append(i).append("\":\"valeurSensible").append(i).append('"');
        }
        big.append('}');
        String shape = PayloadShape.of(json(big.toString()));
        assertTrue(shape.contains("…(+25 clés)"), shape);
        assertFalse(shape.contains("valeurSensible"), "aucune valeur, même au-delà du plafond : " + shape);
    }

    @Test
    @DisplayName("Une clé anormalement longue est TRONQUÉE (le nom, jamais une valeur) et marquée")
    void abnormallyLongKeyNameIsTruncated() {
        String longKey = "k".repeat(PayloadShape.MAX_KEY_CHARS + 40);
        String shape = PayloadShape.of(json("{\"" + longKey + "\":\"v\"}"));
        assertTrue(shape.contains(PayloadShape.TRUNCATED), shape);
        assertFalse(shape.contains(longKey), "le nom complet n'est pas écrit : " + shape);
    }

    @Test
    @DisplayName("Feuilles seules et conteneurs vides : leur type, jamais leur contenu")
    void scalarsAndEmptyContainers() {
        assertEquals("string", PayloadShape.of(json("\"secret\"")));
        assertEquals("number", PayloadShape.of(json("42")));
        assertEquals("boolean", PayloadShape.of(json("true")));
        assertEquals("null", PayloadShape.of(json("null")));
        assertEquals("object{}", PayloadShape.of(json("{}")));
        assertEquals("array<empty>", PayloadShape.of(json("[]")));
    }
}
