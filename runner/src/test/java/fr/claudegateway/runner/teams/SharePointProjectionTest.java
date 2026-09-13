package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * F-108 / SF-108-03 — <b>aucun jeton, cookie, en-tête ni digest ne remonte</b> (arbitrage du PO).
 *
 * <p>Ce test garde les deux verrous : la projection sur liste blanche (même liste pour la page et
 * pour Java), et la forme du script de page lui-même.</p>
 */
class SharePointProjectionTest {

    @Test
    @DisplayName("La liste blanche ne contient que des clés métier")
    void the_whitelist_holds_business_keys_only() {
        for (String key : SharePointProjection.KEEP) {
            String lower = key.toLowerCase(Locale.ROOT);
            for (String forbidden : List.of("digest", "token", "auth", "cookie", "download",
                    "header", "session", "secret", "key")) {
                assertFalse(lower.contains(forbidden), key + " ne doit pas sortir de la page");
            }
        }
    }

    @Test
    @DisplayName("Réponse empoisonnée : digest, adresse pré-authentifiée, jetons disparaissent")
    void a_poisoned_response_loses_every_secret() {
        JsonNode picked = SharePointProjection.pick(TeamsSamples.read("sharepoint-files-secrets.json"));

        String rendered = picked.toString();
        assertFalse(rendered.contains("SECRET"), rendered);
        assertFalse(rendered.contains("downloadUrl"), rendered);
        assertEquals("plan-migration.docx", picked.path("value").get(0).path("Name").asText());
        assertEquals("48213", picked.path("value").get(0).path("Length").asText());
    }

    @Test
    @DisplayName("contextinfo, projeté, ne rend RIEN : le digest ne sort jamais")
    void contextinfo_yields_nothing() {
        JsonNode picked = SharePointProjection.pick(TeamsSamples.read("sharepoint-contextinfo.json"));

        assertEquals(0, picked.size(), picked.toString());
    }

    @Test
    @DisplayName("Le script de page : ni cookies, ni stockage, ni en-têtes ; le corps rendu est projeté")
    void the_page_script_never_touches_authentication() {
        String script = SharePointPage.startScript("cg0123", "https://contoso.sharepoint.com/sites/x",
                "lister les fichiers", "return await call('GET', '/_api/web');");

        for (String forbidden : List.of("document.cookie", "localStorage", "sessionStorage",
                "indexedDB", "getAllResponseHeaders", "headers.get(", ".headers.forEach",
                "r.headers", "c.headers")) {
            assertFalse(script.contains(forbidden), "le script ne touche pas à « " + forbidden + " »");
        }
        // Le seul corps rangé pour la relève est le corps PROJETÉ ; le digest reste une variable
        // locale de la fonction d'appel, et n'est jamais rangé.
        assertTrue(script.contains("body: pick(json)"), script);
        assertFalse(script.contains("body: json"), script);
        assertTrue(script.contains("window.__cgOps[id] = { done: true, out: out }"), script);
        assertFalse(script.contains("out.digest") || script.contains("digest: digest")
                || script.contains("{ digest"), script);
        // La liste de la page est ENGENDRÉE depuis la liste Java.
        SharePointProjection.KEEP.forEach(key -> assertTrue(script.contains("\"" + key + "\""), key));
    }

    @Test
    @DisplayName("Une valeur d'appel d'outil entre dans le script comme littéral échappé")
    void tool_values_are_escaped_literals() {
        assertEquals("\"a\\\"); alert(1); (\\\"\"", SharePointPage.literal("a\"); alert(1); (\""));
    }
}
