package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * F-87 / SF-87-03 — <b>la sonde ne déplace pas la vue de l'utilisateur</b>.
 *
 * <p>La sonde est rejouée à chaque relevé d'état. Si son geste laissait la conversation remontée
 * d'un écran, l'utilisateur verrait sa fenêtre Teams bouger toute seule toutes les minutes — un
 * défaut qu'on n'aurait découvert qu'en production, et qui aurait suffi à faire débrancher l'outil.
 * Le script du geste restaure donc lui-même la position.</p>
 */
@EnabledIf("sourceIsPresent")
class BrowserLinkNudgeTest {

    static boolean sourceIsPresent() {
        return Files.isRegularFile(source());
    }

    private static Path source() {
        return Paths.get("src", "main", "java", "fr", "claudegateway", "runner", "teams",
                "BrowserLink.java");
    }

    @Test
    @DisplayName("Le geste de la sonde remet la vue où elle était")
    void the_probe_gesture_restores_the_view() throws Exception {
        String code = new String(Files.readAllBytes(source()), StandardCharsets.UTF_8);
        int start = code.indexOf("String NUDGE");
        int end = code.indexOf("})()", start);

        assertTrue(start > 0 && end > start, "le script du geste de sonde doit exister");
        String nudge = code.substring(start, end);
        assertTrue(nudge.contains("const before = best.scrollTop"), nudge);
        assertTrue(nudge.contains("setTimeout"), nudge);
        assertTrue(nudge.contains("best.scrollTop = before"),
                "la position d'origine doit être rendue à l'utilisateur : " + nudge);
    }

    @Test
    @DisplayName("Le geste passe par la seule commande d'évaluation autorisée")
    void the_gesture_uses_the_allowed_command() {
        FakeCdpConnection browser = new FakeCdpConnection();
        List<String> said = new ArrayList<>();
        BrowserLink link = BrowserLink.attach(9222, TeamsAdapters.current(),
                url -> url.endsWith("/json/version") ? "{\"Browser\":\"Chrome/140\"}"
                        : "[{\"id\":\"1\",\"type\":\"page\",\"url\":\"https://teams.microsoft.com/\","
                                + "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/d/1\"}]",
                url -> browser, said::add);

        assertTrue(link.nudge(millis -> { }));

        assertEquals(1, browser.sentCommands().stream()
                .filter(CdpCommands.EVALUATE::equals).count());
        browser.sentCommands().forEach(method -> assertTrue(CdpCommands.isAllowed(method), method));
        link.close();
    }
}
