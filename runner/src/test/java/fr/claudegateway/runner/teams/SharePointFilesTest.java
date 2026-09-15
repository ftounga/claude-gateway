package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-108 / SF-108-03 — <b>les réponses modèles de l'API REST SharePoint</b>, construites d'après la
 * documentation publique : ce qui est lu, et ce qui échoue bruyamment.
 */
class SharePointFilesTest {

    private static final SharePointLocation GENERAL = SharePointLocation.parse(
            "https://contoso.sharepoint.com/sites/ProjetIAM/Shared%20Documents/General").location();

    @Test
    @DisplayName("Dossiers et fichiers d'une réponse modèle : nom, chemin, taille, date, id, version")
    void a_model_listing_is_read() {
        SharePointFiles.Listing listing = SharePointFiles.parseListing(GENERAL,
                SharePointProjection.pick(TeamsSamples.read("sharepoint-folders.json")),
                SharePointProjection.pick(TeamsSamples.read("sharepoint-files.json")));

        assertTrue(listing.ok(), listing.gaps().toString());
        assertEquals(4, listing.entries().size());
        SharePointFiles.Entry folder = listing.entries().get(0);
        assertTrue(folder.folder());
        assertEquals("Livrables", folder.name());
        assertEquals(2, folder.itemCount());
        SharePointFiles.Entry file = listing.entries().get(2);
        assertFalse(file.folder());
        assertEquals("plan-migration.docx", file.name());
        assertEquals(48_213L, file.size());
        assertEquals("3.0", file.version());
        assertEquals("2026-09-12T09:30:00Z", file.modified());
        assertEquals("00000000-0000-0000-0000-00000000a001", file.id());
    }

    @Test
    @DisplayName("Forme verbose (d.results) au lieu de nometadata : ZÉRO élément, manque nommé")
    void a_verbose_shape_fails_loudly() {
        SharePointFiles.Listing listing = SharePointFiles.parseListing(GENERAL,
                SharePointProjection.pick(TeamsSamples.read("sharepoint-folders.json")),
                SharePointProjection.pick(TeamsSamples.read("sharepoint-files-verbose.json")));

        assertTrue(listing.entries().isEmpty());
        assertEquals(TeamsGapKind.SHAPE_MISMATCH, listing.gaps().get(0).kind());
        assertTrue(listing.gaps().get(0).detail().contains("à confirmer sur poste réel"));
    }

    @Test
    @DisplayName("Un fichier sans taille : RIEN n'est rendu, jamais une liste à moitié")
    void a_partial_listing_is_refused_entirely() {
        SharePointFiles.Listing listing = SharePointFiles.parseListing(GENERAL,
                SharePointProjection.pick(TeamsSamples.read("sharepoint-folders.json")),
                SharePointProjection.pick(TeamsSamples.read("sharepoint-files-partial.json")));

        assertTrue(listing.entries().isEmpty());
        assertEquals(TeamsGapKind.SHAPE_MISMATCH, listing.gaps().get(0).kind());
        assertTrue(listing.gaps().get(0).detail().contains("Length"), listing.gaps().toString());
    }

    @Test
    @DisplayName("Un fichier modèle : métadonnées lues ; sans champ obligatoire : refus nommé")
    void a_model_file_is_read() {
        SharePointFiles.Item item = SharePointFiles.parseFile(GENERAL.child("plan-migration.docx"),
                SharePointProjection.pick(TeamsSamples.read("sharepoint-file.json")));
        assertTrue(item.ok());
        assertEquals(48_213L, item.entry().size());

        SharePointFiles.Item broken = SharePointFiles.parseFile(GENERAL.child("x"),
                SharePointProjection.pick(TeamsSamples.read("sharepoint-contextinfo.json")));
        assertFalse(broken.ok());
        assertEquals(TeamsGapKind.SHAPE_MISMATCH, broken.gaps().get(0).kind());
    }

    @Test
    @DisplayName("403 et 404 deviennent des manques nommés, avec le message Microsoft")
    void http_errors_are_named() {
        TeamsGap denied = SharePointFiles.gapOf(
                new SharePointPage.Answer(false, 403, null, "Access denied."), "x");
        TeamsGap missing = SharePointFiles.gapOf(
                new SharePointPage.Answer(false, 404, null, "File Not Found."), "x");

        assertEquals(TeamsGapKind.ACCESS_DENIED, denied.kind());
        assertTrue(denied.detail().contains("Access denied."));
        assertEquals(TeamsGapKind.NOT_FOUND, missing.kind());
    }

    // ------------------------------------------------------- F-108 / SF-108-07 : droits (v2.1)

    @Test
    @DisplayName("Le script des droits lit le corps BRUT dans la page, ne rend qu'un code, ne projette rien")
    void the_drive_item_access_script_decides_in_page_and_leaks_nothing() {
        String script = SharePointFiles.driveItemAccessScript("b!DRIVE-CAGIP", "01ITEMCAGIP");

        // Il vise la forme RÉELLE v2.1 et lit les booléens par leur nom, sur le corps brut.
        assertTrue(script.contains("/_api/v2.1/drives/b!DRIVE-CAGIP/items/01ITEMCAGIP"), script);
        assertTrue(script.contains("/labelPolicies"), script);
        assertTrue(script.contains("accessViewpoint"), script);
        assertTrue(script.contains("canDownload"), script);
        assertTrue(script.contains("irmCapabilities"), script);
        assertTrue(script.contains("canExtract"), script);
        // Il ne rend qu'un CODE machine — jamais accessViewpoint, jamais une adresse signée.
        assertTrue(script.contains(SharePointItemAccess.CODE_DOWNLOAD), script);
        assertTrue(script.contains(SharePointItemAccess.CODE_EXTRACT), script);
        // Il NE passe PAS par la projection (pick projette et écarte les clés « download ») : il lit le
        // brut dans la page et n'en fait sortir qu'un verdict.
        assertFalse(script.contains("pick("), script);
        assertFalse(script.contains("body:"), "le script ne rend jamais un corps, seulement un verdict : " + script);
    }
}
