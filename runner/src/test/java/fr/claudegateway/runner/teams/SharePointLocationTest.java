package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-108 / SF-108-03 — <b>une adresse web devient un emplacement, ou un refus nommé</b>.
 */
class SharePointLocationTest {

    @Test
    @DisplayName("Lien de bibliothèque d'une équipe : origine, site et chemin relatif serveur")
    void a_team_library_address_is_understood() {
        SharePointLocation location = ok(
                "https://contoso.sharepoint.com/sites/ProjetIAM/Shared%20Documents/General");

        assertEquals("https://contoso.sharepoint.com", location.origin());
        assertEquals("/sites/ProjetIAM", location.sitePath());
        assertEquals("/sites/ProjetIAM/Shared Documents/General", location.serverPath());
        assertEquals("https://contoso.sharepoint.com/sites/ProjetIAM", location.siteUrl());
        assertEquals("ProjetIAM › Shared Documents › General", location.label());
        assertEquals("General", location.name());
    }

    @Test
    @DisplayName("Vue AllItems.aspx : le chemin est lu dans « id »")
    void the_allitems_view_carries_its_path_in_id() {
        SharePointLocation location = ok("https://contoso.sharepoint.com/sites/ProjetIAM/Shared%20"
                + "Documents/Forms/AllItems.aspx?id=%2Fsites%2FProjetIAM%2FShared%20Documents%2F"
                + "General%2FLivrables&viewid=00000000");

        assertEquals("/sites/ProjetIAM/Shared Documents/General/Livrables", location.serverPath());
    }

    @Test
    @DisplayName("Lien direct « /:f:/r/ » : la suite est le chemin réel ; requête retirée")
    void a_direct_link_is_followed_by_its_path() {
        SharePointLocation location = ok("https://contoso.sharepoint.com/:f:/r/teams/Finance/"
                + "Shared%20Documents/Budget?csf=1&web=1&e=abc");

        assertEquals("/teams/Finance", location.sitePath());
        assertEquals("/teams/Finance/Shared Documents/Budget", location.serverPath());
    }

    @Test
    @DisplayName("OneDrive professionnel : reconnu comme tel, libellé « OneDrive »")
    void a_business_onedrive_is_recognised() {
        SharePointLocation location = ok("https://contoso-my.sharepoint.com/personal/"
                + "francky_fabrique_invalid/Documents/Livrables");

        assertTrue(location.isOneDrive());
        assertEquals("OneDrive › Documents › Livrables", location.label());
    }

    @Test
    @DisplayName("L'adresse de téléchargement est construite ici, non signée")
    void the_download_address_is_built_and_unsigned() {
        SharePointLocation file = ok("https://contoso.sharepoint.com/sites/ProjetIAM/Shared%20"
                + "Documents/General/notes%20%231.txt");

        String download = file.downloadUrl();
        assertTrue(download.startsWith("https://contoso.sharepoint.com/sites/ProjetIAM/_layouts/15/"
                + "download.aspx?SourceUrl=%2Fsites%2FProjetIAM%2FShared%20Documents"), download);
        assertTrue(download.contains("notes%20%231.txt"), download);
        assertFalse(download.toLowerCase().contains("tempauth"), download);
        assertTrue(MicrosoftDomains.isAllowed(download));
    }

    @Test
    @DisplayName("Refus nommés : hors domaine, grand public, partage opaque, identification, traversée")
    void unknown_addresses_are_refused_with_a_reason() {
        refused("https://example.com/sites/x/Shared%20Documents", "domaine Microsoft autorisé");
        refused("https://onedrive.live.com/?id=root", "grand public");
        refused("https://contoso.sharepoint.com/:w:/s/ProjetIAM/EaBcD?e=xyz", "partage opaque");
        refused("https://login.microsoftonline.com/common/oauth2", "identification");
        refused("https://teams.microsoft.com/v2/", "n'héberge pas");
        refused("http://contoso.sharepoint.com/sites/x/Docs", "https://");
        refused("https://contoso.sharepoint.com/sites/ProjetIAM", "site, pas une bibliothèque");
        refused("https://contoso.sharepoint.com/sites/x/Docs/../../../etc", "relatif");
        refused("", "aucune adresse");
    }

    private static SharePointLocation ok(String url) {
        SharePointLocation.Parsed parsed = SharePointLocation.parse(url);
        assertTrue(parsed.ok(), parsed.refusal());
        return parsed.location();
    }

    private static void refused(String url, String because) {
        SharePointLocation.Parsed parsed = SharePointLocation.parse(url);
        assertFalse(parsed.ok(), url);
        assertTrue(parsed.refusal().contains(because), url + " → " + parsed.refusal());
    }
}
