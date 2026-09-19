package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.diag.RunnerDiag;

/**
 * Le maintien de l'onglet Teams (F-122 / SF-122-07) — rouvre l'onglet fermé, ne double jamais, et
 * ne casse jamais la boucle. Éprouvé sans navigateur : la liste des cibles et l'ouverture sont injectées.
 */
class TeamsTabOpenerTest {

    private final List<String> opened = new ArrayList<>();
    private final List<String> said = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanDiag() {
        RunnerDiag.reset();
    }

    private TeamsTabOpener opener(String listJson) {
        return new TeamsTabOpener(() -> listJson, opened::add, said::add);
    }

    @Test
    @DisplayName("aucun onglet Teams ⇒ rouvre teams.microsoft.com")
    void reopensWhenNoTeamsTab() {
        opener("[{\"id\":\"1\",\"type\":\"page\",\"url\":\"https://example.com/\","
                + "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/d/1\"}]").ensureTeamsTab();

        assertEquals(List.of(BrowserLaunchAdvice.TEAMS_URL), opened,
                "l'onglet Teams doit être rouvert quand il a été fermé");
    }

    @Test
    @DisplayName("aucune cible du tout (liste vide) ⇒ rouvre teams.microsoft.com")
    void reopensWhenNoTargets() {
        opener("[]").ensureTeamsTab();
        assertEquals(List.of(BrowserLaunchAdvice.TEAMS_URL), opened);
    }

    @Test
    @DisplayName("un onglet Teams est déjà là ⇒ aucune ouverture (idempotent)")
    void doesNothingWhenTeamsTabPresent() {
        opener("[{\"id\":\"1\",\"type\":\"page\",\"url\":\"https://teams.microsoft.com/v2/\","
                + "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/d/1\"}]").ensureTeamsTab();

        assertTrue(opened.isEmpty(), "on ne double jamais un onglet Teams déjà ouvert");
    }

    @Test
    @DisplayName("une page d'identification Microsoft est là ⇒ aucune ouverture (relogin SF-122-03)")
    void doesNothingOnSignInPage() {
        opener("[{\"id\":\"1\",\"type\":\"page\",\"url\":\"https://login.microsoftonline.com/common/oauth2/\","
                + "\"webSocketDebuggerUrl\":\"ws://127.0.0.1:9222/d/1\"}]").ensureTeamsTab();

        assertTrue(opened.isEmpty(),
                "une identification est le domaine du relogin, pas d'un nouvel onglet");
    }

    @Test
    @DisplayName("liste illisible / port muet ⇒ avalé (best-effort), aucune exception, aucune ouverture")
    void listFailureIsSwallowed() {
        TeamsTabOpener opener = new TeamsTabOpener(() -> {
            throw new IllegalStateException("port muet");
        }, opened::add, said::add);

        opener.ensureTeamsTab(); // ne doit pas propager

        assertTrue(opened.isEmpty());
        assertTrue(RunnerDiag.drain(100).events().stream()
                        .anyMatch(e -> e.cat().equals("chrome") && e.code().equals("teams_tab")
                                && "error".equals(e.fields().get("result"))),
                "un port muet doit être diagnostiqué (F-132), jamais fatal");
    }

    @Test
    @DisplayName("une réouverture émet un diagnostic F-132 chrome/teams_tab")
    void reopenEmitsDiag() {
        opener("[]").ensureTeamsTab();

        assertTrue(RunnerDiag.drain(100).events().stream()
                        .anyMatch(e -> e.cat().equals("chrome") && e.code().equals("teams_tab")
                                && "reopened".equals(e.fields().get("result"))),
                "la réouverture doit être visible dans le diagnostic");
    }

    @Test
    @DisplayName("une ouverture qui échoue est avalée (best-effort), diagnostiquée en erreur")
    void openFailureIsSwallowed() {
        TeamsTabOpener opener = new TeamsTabOpener(() -> "[]", url -> {
            throw new IllegalStateException("ouverture refusée");
        }, said::add);

        opener.ensureTeamsTab();

        assertTrue(RunnerDiag.drain(100).events().stream()
                        .anyMatch(e -> e.cat().equals("chrome") && e.code().equals("teams_tab")
                                && "error".equals(e.fields().get("result"))),
                "une ouverture ratée doit être diagnostiquée, jamais fatale");
    }
}
