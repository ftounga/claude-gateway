package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-108 / SF-108-04 — <b>les briques des écritures</b> : noms refusés, erreurs nommées, scripts qui ne
 * lisent pas le fichier et ne rendent jamais le digest.
 */
class SharePointWritesTest {

    @Test
    @DisplayName("Noms refusés par SharePoint : refus avant tout geste")
    void invalid_names() {
        assertEquals("", SharePointWrites.invalidName("Livrables 2026"));
        for (String bad : List.of("", "   ", ".", "..", "a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b",
                "a>b", "a|b", "fin.", "x".repeat(256))) {
            assertFalse(SharePointWrites.invalidName(bad).isEmpty(), "« " + bad + " » doit être refusé");
        }
    }

    @Test
    @DisplayName("Erreurs d'écriture : existe déjà, verrouillé, accès refusé")
    void write_errors_are_named() {
        assertEquals(TeamsGapKind.ALREADY_EXISTS, SharePointWrites.gapOfWrite(new SharePointPage.Answer(false,
                400, null, "A file with the name x already exists."), "x").kind());
        assertEquals(TeamsGapKind.WRITE_FAILED, SharePointWrites.gapOfWrite(new SharePointPage.Answer(false,
                423, null, "The file is locked for shared use."), "x").kind());
        assertEquals(TeamsGapKind.ACCESS_DENIED, SharePointWrites.gapOfWrite(new SharePointPage.Answer(false,
                403, null, "Access denied."), "x").kind());
        assertEquals(TeamsGapKind.WRITE_FAILED, SharePointWrites.gapOfWrite(new SharePointPage.Answer(false,
                500, null, ""), "x").kind());
    }

    @Test
    @DisplayName("Le champ de dépôt est à nous, et le script de dépôt ne lit jamais le fichier")
    void the_upload_never_reads_the_file_in_page() {
        String input = SharePointWrites.inputScript("cg-drop-1");
        assertTrue(input.contains("createElement('input')"));
        assertTrue(input.contains("i.type = 'file'"));

        String start = SharePointPage.startScript("cg1", "https://contoso.sharepoint.com/sites/x",
                "déposer le fichier", "const input = document.getElementById(\"cg-drop-1\");"
                        + " const file = input.files[0]; return await call('POST', '/x', { body: file });");
        for (String forbidden : List.of("FileReader", "arrayBuffer", ".text()", "readAsDataURL",
                "document.cookie", "localStorage")) {
            assertFalse(start.contains(forbidden), forbidden);
        }
        // Le digest est posé dans l'en-tête de la requête, et jamais rangé pour la relève.
        assertTrue(start.contains("headers['X-RequestDigest'] = digest"));
        assertFalse(start.contains("digest }") || start.contains("digest: digest"));
    }
}
