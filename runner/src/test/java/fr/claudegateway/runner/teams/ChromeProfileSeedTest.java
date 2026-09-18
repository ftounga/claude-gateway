package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * F-122 / SF-122-05 — amorce ciblée du profil du Chrome managé : micro pré-autorisé pour la seule
 * origine Teams, idempotent, sans écraser les autres réglages.
 */
class ChromeProfileSeedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // --- Fusion pure ---------------------------------------------------------------------------

    @Test
    @DisplayName("Pose media_stream_mic = allow pour les deux origines Teams à partir d'un objet vide")
    void seeds_mic_allow_for_teams_origins() {
        ObjectNode root = MAPPER.createObjectNode();

        ChromeProfileSeed.withTeamsMicAllowed(root);

        JsonNode mic = root.path("profile").path("content_settings").path("exceptions")
                .path("media_stream_mic");
        assertEquals(ChromeProfileSeed.ALLOW, mic.path(ChromeProfileSeed.TEAMS_ORIGIN).path("setting").asInt());
        assertEquals(ChromeProfileSeed.ALLOW, mic.path(ChromeProfileSeed.TEAMS_WILDCARD).path("setting").asInt());
    }

    @Test
    @DisplayName("Idempotence : deux fusions successives produisent le même contenu, sans doublon")
    void merge_is_idempotent() {
        ObjectNode once = ChromeProfileSeed.withTeamsMicAllowed(MAPPER.createObjectNode());
        String first = once.toString();

        String second = ChromeProfileSeed.withTeamsMicAllowed(once).toString();

        assertEquals(first, second);
        JsonNode mic = once.path("profile").path("content_settings").path("exceptions")
                .path("media_stream_mic");
        assertEquals(2, mic.size(), "exactement les deux motifs Teams, aucun doublon");
    }

    @Test
    @DisplayName("Non-clobber : les autres clés et exceptions du Preferences sont préservées")
    void preserves_other_keys() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("some_top_level_flag", true);
        ObjectNode profile = root.putObject("profile");
        profile.put("name", "Personne");
        ObjectNode exceptions = profile.putObject("content_settings").putObject("exceptions");
        // Une autre origine déjà réglée pour le micro, et une autre section de content settings.
        exceptions.putObject("media_stream_mic").putObject("https://autre.example.com:443,*")
                .put("setting", 2);
        exceptions.putObject("cookies").putObject("https://autre.example.com:443,*").put("setting", 1);

        ChromeProfileSeed.withTeamsMicAllowed(root);

        assertTrue(root.path("some_top_level_flag").asBoolean());
        assertEquals("Personne", root.path("profile").path("name").asText());
        JsonNode mic = root.path("profile").path("content_settings").path("exceptions")
                .path("media_stream_mic");
        // L'autre origine micro survit, et les deux origines Teams sont ajoutées.
        assertEquals(2, mic.path("https://autre.example.com:443,*").path("setting").asInt());
        assertEquals(ChromeProfileSeed.ALLOW, mic.path(ChromeProfileSeed.TEAMS_ORIGIN).path("setting").asInt());
        assertEquals(ChromeProfileSeed.ALLOW, mic.path(ChromeProfileSeed.TEAMS_WILDCARD).path("setting").asInt());
        // L'autre section de content settings est intacte.
        assertEquals(1, root.path("profile").path("content_settings").path("exceptions")
                .path("cookies").path("https://autre.example.com:443,*").path("setting").asInt());
    }

    // --- I/O sur le profil ---------------------------------------------------------------------

    @Test
    @DisplayName("seed crée Default/Preferences avec l'allow Teams, et reste idempotent au second appel")
    void seed_creates_then_is_idempotent(@TempDir Path profileDir) throws IOException {
        ChromeProfileSeed.seed(profileDir, null);

        Path prefs = profileDir.resolve(ChromeProfileSeed.PREFERENCES_PATH);
        assertTrue(Files.exists(prefs));
        JsonNode mic = MAPPER.readTree(Files.readString(prefs))
                .path("profile").path("content_settings").path("exceptions").path("media_stream_mic");
        assertEquals(ChromeProfileSeed.ALLOW, mic.path(ChromeProfileSeed.TEAMS_ORIGIN).path("setting").asInt());
        assertEquals(ChromeProfileSeed.ALLOW, mic.path(ChromeProfileSeed.TEAMS_WILDCARD).path("setting").asInt());

        String after1 = Files.readString(prefs);
        ChromeProfileSeed.seed(profileDir, null);
        assertEquals(after1, Files.readString(prefs), "second amorçage : contenu identique");
    }

    @Test
    @DisplayName("seed fusionne dans un Preferences existant sans écraser les autres clés")
    void seed_merges_into_existing(@TempDir Path profileDir) throws IOException {
        Path prefs = profileDir.resolve(ChromeProfileSeed.PREFERENCES_PATH);
        Files.createDirectories(prefs.getParent());
        Files.writeString(prefs, "{\"profile\":{\"name\":\"Perso\"},\"gardien\":42}");

        ChromeProfileSeed.seed(profileDir, null);

        JsonNode root = MAPPER.readTree(Files.readString(prefs));
        assertEquals(42, root.path("gardien").asInt());
        assertEquals("Perso", root.path("profile").path("name").asText());
        assertEquals(ChromeProfileSeed.ALLOW, root.path("profile").path("content_settings")
                .path("exceptions").path("media_stream_mic")
                .path(ChromeProfileSeed.TEAMS_ORIGIN).path("setting").asInt());
    }

    @Test
    @DisplayName("Un Preferences corrompu n'est pas écrasé et seed ne lève pas")
    void corrupt_preferences_is_left_untouched(@TempDir Path profileDir) throws IOException {
        Path prefs = profileDir.resolve(ChromeProfileSeed.PREFERENCES_PATH);
        Files.createDirectories(prefs.getParent());
        String corrupt = "{ ceci n'est pas du JSON";
        Files.writeString(prefs, corrupt);

        assertDoesNotThrow(() -> ChromeProfileSeed.seed(profileDir, null));

        assertEquals(corrupt, Files.readString(prefs), "le fichier corrompu est laissé tel quel");
    }

    @Test
    @DisplayName("seed ne lève jamais même si le message est nul et le dossier neuf")
    void seed_never_throws(@TempDir Path base) {
        Path fresh = base.resolve("profil-neuf");
        assertDoesNotThrow(() -> ChromeProfileSeed.seed(fresh, null));
        assertTrue(Files.exists(fresh.resolve(ChromeProfileSeed.PREFERENCES_PATH)));
    }

    @Test
    @DisplayName("Aucun flag média large : la portée reste micro/Teams uniquement (garde de conception)")
    void scope_is_mic_teams_only() {
        // Garde documentaire : les motifs restent bornés à l'origine Teams.
        assertTrue(ChromeProfileSeed.TEAMS_ORIGIN.contains("teams.microsoft.com"));
        assertTrue(ChromeProfileSeed.TEAMS_WILDCARD.contains("teams.microsoft.com"));
        assertFalse(ChromeProfileSeed.TEAMS_ORIGIN.contains("*.*"));
    }
}
