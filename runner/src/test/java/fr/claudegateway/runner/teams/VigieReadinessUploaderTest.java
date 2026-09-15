package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** F-122 / SF-122-03 — la remontée de l'instantané de readiness : refus propre et corps attendu. */
class VigieReadinessUploaderTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("sans gateway ni jeton, la remontée lève une IOException sans casser l'appelant")
    void unavailableThrows() {
        VigieReadinessUploader uploader = VigieReadinessUploader.unavailable("pas de gateway");

        IOException error = assertThrows(IOException.class, () -> uploader.upload(report()));
        assertTrue(error.getMessage().contains("pas de gateway"));
    }

    @Test
    @DisplayName("un poste sans jeton ne fait rien remonter")
    void noTokenIsRefused() {
        VigieReadinessUploader uploader =
                VigieReadinessUploader.over(null, "https://gw.example.com/api", "  ");

        assertThrows(IOException.class, () -> uploader.upload(report()));
    }

    @Test
    @DisplayName("le corps JSON porte les cinq champs attendus")
    void jsonCarriesTheFiveFields() throws IOException {
        String json = VigieReadinessUploader.toJson(mapper,
                new VigieReadinessReport(true, false, true, false, "détail"));

        assertTrue(json.contains("\"chromeReachable\":true"), json);
        assertTrue(json.contains("\"teamsConnected\":false"), json);
        assertTrue(json.contains("\"teamsSignInRequired\":true"), json);
        assertTrue(json.contains("\"teamsReadTest\":false"), json);
        assertTrue(json.contains("\"detail\":\"détail\""), json);
    }

    private static VigieReadinessReport report() {
        return new VigieReadinessReport(true, true, false, true, "ok");
    }
}
