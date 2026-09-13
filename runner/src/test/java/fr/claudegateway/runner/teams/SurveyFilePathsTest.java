package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-108 / SF-108-03 — <b>le relevé réel relève aussi les chemins SharePoint et OneDrive</b>
 * (arbitrage du PO, point 5) : ceux que l'adaptateur fichiers appelle sont classés sous leur nom, et
 * le rapport les regroupe — sans corps, sans requête, sans tenant.
 */
class SurveyFilePathsTest {

    @Test
    @DisplayName("Les appels de l'adaptateur fichiers sont reconnus ; les autres non")
    void the_file_adapter_endpoints_are_named() {
        assertEquals("SHAREPOINT_FOLDER", SharePointFiles.endpointOf("https://contoso.sharepoint.com/"
                + "sites/ProjetIAM/_api/web/GetFolderByServerRelativePath(decodedurl='x')/Files"));
        assertEquals("SHAREPOINT_CONTEXTINFO",
                SharePointFiles.endpointOf("https://contoso.sharepoint.com/sites/ProjetIAM/_api/contextinfo"));
        assertEquals("SHAREPOINT_DOWNLOAD", SharePointFiles.endpointOf("https://contoso.sharepoint.com/"
                + "sites/ProjetIAM/_layouts/15/download.aspx?SourceUrl=%2Fsites"));
        assertEquals("ONEDRIVE_PERSONAL_URL", SharePointFiles.endpointOf("https://contoso-my.sharepoint.com/"
                + "_api/SP.UserProfiles.PeopleManager/GetMyProperties"));
        assertEquals("", SharePointFiles.endpointOf("https://contoso.sharepoint.com/sites/x/_api/v2.1/drives"));
        assertEquals("", SharePointFiles.endpointOf("https://teams.microsoft.com/_api/contextinfo"));
    }

    @Test
    @DisplayName("Le rapport a sa section fichiers, classée, sans tenant ni requête")
    void the_report_groups_file_paths() {
        SurveyFakeBrowser tab = new SurveyFakeBrowser();
        NetworkSurvey survey = new NetworkSurvey(Runnable::run);
        survey.watchTeamsTab(tab);
        tab.respond("", "https://contoso.sharepoint.com/sites/ProjetIAM/_api/web/"
                + "GetFolderByServerRelativePath(decodedurl='%2Fsites%2FProjetIAM%2FShared')/Files"
                + "?$select=Name&tempauth=SECRET", "Fetch", "application/json", 200);
        tab.respond("", "https://teams.microsoft.com/api/mt/emea/beta/meetings/19:meeting_abc@thread.v2",
                "Fetch", "application/json", 200);

        SurveyReport report = new SurveyReport(survey.snapshot(), Instant.EPOCH,
                Instant.EPOCH.plusSeconds(60), false, "Chrome/140", "v1");

        assertEquals(1, report.filePaths().size());
        assertEquals("SHAREPOINT_FOLDER", report.filePaths().get(0).classification());
        String markdown = report.markdown();
        assertTrue(markdown.contains("## Fichiers SharePoint et OneDrive (F-108)"));
        assertTrue(markdown.contains("à confirmer sur poste réel"));
        for (String forbidden : List.of("SECRET", "contoso", "ProjetIAM", "tempauth", "?")) {
            assertFalse(markdown.contains(forbidden), forbidden);
        }
    }
}
