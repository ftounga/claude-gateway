package fr.claudegateway.runner.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-132 / SF-132-01 — l'expurgation à la source : aucun secret, aucune URL brute, aucun contenu ne
 * survit. Invariant de confidentialité (poste client/banque).
 */
class RunnerDiagRedactionTest {

    @Test
    @DisplayName("Une URL brute dans un message est remplacée par sa classe, jamais laissée telle quelle")
    void message_replaces_raw_urls_with_class() {
        String raw = "onglet ouvert sur https://contoso.sharepoint.com/sites/secret/Doc.docx?x=1";

        String safe = RunnerDiagRedaction.message(raw);

        assertFalse(safe.contains("contoso"), "aucun sous-domaine locataire ne doit survivre");
        assertFalse(safe.contains("sharepoint.com/sites"), "aucun chemin ne doit survivre");
        assertFalse(safe.contains("http"), "aucune URL brute ne doit survivre");
        assertTrue(safe.contains("<sharepoint>"), "l'URL est remplacée par sa classe : " + safe);
    }

    @Test
    @DisplayName("Un message trop long est tronqué et les contrôle-caractères sont ôtés")
    void message_is_trimmed_and_truncated() {
        String raw = "a\n\tb" + "x".repeat(1000);
        String safe = RunnerDiagRedaction.message(raw);
        assertTrue(safe.length() <= RunnerDiagRedaction.MAX_MESSAGE_CHARS + 1);
        assertFalse(safe.contains("\n"));
        assertFalse(safe.contains("\t"));
    }

    @Test
    @DisplayName("Un message vide/nul rend null (le message est optionnel)")
    void blank_message_is_null() {
        assertNull(RunnerDiagRedaction.message(null));
        assertNull(RunnerDiagRedaction.message("   "));
    }

    @Test
    @DisplayName("Seuls les scalaires survivent ; toute valeur non scalaire (carte/liste) est écartée")
    void fields_keep_only_scalars() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("state", "REACHABLE");
        raw.put("port", 9222);
        raw.put("ok", true);
        raw.put("nested", Map.of("secret", "value"));
        raw.put("list", java.util.List.of("a", "b"));

        Map<String, Object> safe = RunnerDiagRedaction.fields(raw);

        assertEquals("REACHABLE", safe.get("state"));
        assertEquals(9222, safe.get("port"));
        assertEquals(true, safe.get("ok"));
        assertFalse(safe.containsKey("nested"), "une carte imbriquée est écartée");
        assertFalse(safe.containsKey("list"), "une liste est écartée");
    }

    @Test
    @DisplayName("Une valeur de champ texte portant une URL est expurgée (classe), jamais brute")
    void field_string_value_is_redacted() {
        Map<String, Object> safe = RunnerDiagRedaction.fields(
                Map.of("url", "https://login.microsoftonline.com/tenant/oauth2/authorize?token=abc123"));
        String value = (String) safe.get("url");
        assertFalse(value.contains("token=abc123"), "aucun secret ne doit survivre");
        assertFalse(value.contains("microsoftonline"));
        assertTrue(value.contains("<sign_in>"), value);
    }

    @Test
    @DisplayName("urlClass rend une classe grossière — jamais l'adresse, jamais le locataire")
    void url_class_is_coarse() {
        assertEquals("none", RunnerDiagRedaction.urlClass(null));
        assertEquals("none", RunnerDiagRedaction.urlClass(""));
        assertEquals("sign_in",
                RunnerDiagRedaction.urlClass("https://login.microsoftonline.com/x"));
        assertEquals("teams", RunnerDiagRedaction.urlClass("https://teams.microsoft.com/_#/meeting/x"));
        assertEquals("sharepoint",
                RunnerDiagRedaction.urlClass("https://contoso.sharepoint.com/sites/x"));
        assertEquals("other", RunnerDiagRedaction.urlClass("https://example.com/path"));
    }

    @Test
    @DisplayName("Un code/catégorie est réduit à [a-z0-9_] et tronqué")
    void labels_are_sanitized() {
        assertEquals("chrome_state", RunnerDiagRedaction.label("Chrome State!"));
        assertEquals("teams", RunnerDiagRedaction.label("  Teams  "));
        assertNull(RunnerDiagRedaction.label("  "));
    }
}
