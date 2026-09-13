package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import fr.claudegateway.runner.OperatingSystem;

/**
 * F-108 / SF-108-03 — <b>préférer le dossier synchronisé quand il existe</b> (cadrage §5.1).
 */
class SyncedLibrariesTest {

    @TempDir
    Path home;

    @Test
    @DisplayName("Windows : bibliothèque d'équipe synchronisée sous %USERPROFILE%\\<organisation>")
    void windows_team_library_is_resolved() throws Exception {
        Files.createDirectories(home.resolve("OneDrive - Contoso"));
        Path general = Files.createDirectories(
                home.resolve("Contoso").resolve("Projet IAM - Documents").resolve("General"));

        SyncedLibraries synced = new SyncedLibraries(home, Map.of(), OperatingSystem.WINDOWS);

        assertEquals(general, synced.resolve(location(
                "https://contoso.sharepoint.com/sites/ProjetIAM/Shared%20Documents/General")).orElseThrow());
    }

    @Test
    @DisplayName("Windows : OneDrive professionnel par %OneDriveCommercial%")
    void windows_onedrive_is_resolved_from_env() throws Exception {
        Path root = Files.createDirectories(home.resolve("OneDrive - Contoso"));
        Path livrables = Files.createDirectories(root.resolve("Livrables"));

        SyncedLibraries synced = new SyncedLibraries(home,
                Map.of("OneDriveCommercial", root.toString()), OperatingSystem.WINDOWS);

        assertEquals(livrables, synced.resolve(location("https://contoso-my.sharepoint.com/personal/"
                + "francky_fabrique_invalid/Documents/Livrables")).orElseThrow());
    }

    @Test
    @DisplayName("macOS : CloudStorage/OneDrive-SharedLibraries-<organisation>")
    void macos_shared_library_is_resolved() throws Exception {
        Path general = Files.createDirectories(home.resolve("Library").resolve("CloudStorage")
                .resolve("OneDrive-SharedLibraries-Contoso").resolve("ProjetIAM - Documents partages")
                .resolve("General"));

        SyncedLibraries synced = new SyncedLibraries(home, Map.of(), OperatingSystem.MACOS);

        assertEquals(general, synced.resolve(location(
                "https://contoso.sharepoint.com/sites/ProjetIAM/Shared%20Documents/General")).orElseThrow());
    }

    @Test
    @DisplayName("Rien de synchronisé, ou un dossier absent : aucun chemin supposé")
    void nothing_is_guessed() throws Exception {
        Files.createDirectories(home.resolve("OneDrive - Contoso"));
        Files.createDirectories(home.resolve("Contoso").resolve("Projet IAM - Documents"));
        SyncedLibraries synced = new SyncedLibraries(home, Map.of(), OperatingSystem.WINDOWS);

        assertTrue(synced.resolve(location(
                "https://contoso.sharepoint.com/sites/ProjetIAM/Shared%20Documents/Absent")).isEmpty());
        assertTrue(synced.resolve(location(
                "https://contoso.sharepoint.com/sites/AutreSite/Shared%20Documents")).isEmpty());
        assertTrue(new SyncedLibraries(home, Map.of(), OperatingSystem.LINUX).roots().isEmpty());
    }

    @Test
    @DisplayName("Correspondance des noms : titre du site sans espaces, bibliothèque par défaut traduite")
    void library_names_match_loosely_but_exactly() {
        assertTrue(SyncedLibraries.matchesLibrary("Projet IAM - Documents", "ProjetIAM",
                "Shared Documents"));
        assertTrue(SyncedLibraries.matchesLibrary("Finance - Budget", "Finance", "Budget"));
        assertFalse(SyncedLibraries.matchesLibrary("Finance - Budget", "Finance", "Shared Documents"));
        assertFalse(SyncedLibraries.matchesLibrary("Projet IAM", "ProjetIAM", "Shared Documents"));
    }

    private static SharePointLocation location(String url) {
        return SharePointLocation.parse(url).location();
    }
}
