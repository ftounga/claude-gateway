package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-87 / SF-87-02 — le rattachement, éprouvé contre un <b>faux navigateur</b>.
 *
 * <p>Nous n'avons ni compte Teams de test, ni navigateur dans l'intégration continue. Ce qui est
 * vérifié ici, c'est donc la <b>mécanique</b> : quel onglet on retient, et surtout ce qu'on dit
 * quand on ne peut pas se rattacher — la partie que l'utilisateur rencontrera le plus souvent.</p>
 */
class BrowserLinkTest {

    private static final String LIST_WITH_TEAMS = """
            [
              {"id":"1","type":"page","url":"https://www.example.com/","title":"Autre chose",
               "webSocketDebuggerUrl":"ws://127.0.0.1:9222/devtools/page/1"},
              {"id":"2","type":"page","url":"https://teams.microsoft.com/v2/?culture=fr-fr",
               "title":"Microsoft Teams","webSocketDebuggerUrl":"ws://127.0.0.1:9222/devtools/page/2"}
            ]""";

    private static final String LIST_WITHOUT_TEAMS = """
            [{"id":"1","type":"page","url":"https://www.example.com/","title":"Autre chose",
              "webSocketDebuggerUrl":"ws://127.0.0.1:9222/devtools/page/1"}]""";

    private static final String LIST_ON_SIGN_IN = """
            [{"id":"1","type":"page","url":"https://login.microsoftonline.com/common/oauth2/authorize",
              "title":"Se connecter","webSocketDebuggerUrl":"ws://127.0.0.1:9222/devtools/page/1"}]""";

    private static final String VERSION = "{\"Browser\":\"Chrome/140.0.7339.16\"}";

    private final TeamsAdapter adapter = TeamsAdapters.current();
    private final List<String> said = new ArrayList<>();

    @Test
    @DisplayName("On se rattache à l'onglet Teams, et à aucun autre")
    void attaches_to_the_teams_tab_only() {
        List<String> opened = new ArrayList<>();
        BrowserLink link = BrowserLink.attach(9222, adapter, fake(LIST_WITH_TEAMS), url -> {
            opened.add(url);
            return new FakeCdpConnection();
        }, said::add);

        assertEquals(List.of("ws://127.0.0.1:9222/devtools/page/2"), opened);
        assertEquals("https://teams.microsoft.com/v2/", link.teamsTabUrl(),
                "l'adresse retenue est amputée de sa chaîne de requête");
        assertEquals("Chrome/140.0.7339.16", link.browser());
        assertTrue(said.get(0).contains("Aucun cookie, aucun jeton ne remonte"), said.toString());
        link.close();
    }

    @Test
    @DisplayName("Port fermé : l'échec DONNE la ligne de commande à coller")
    void closed_port_hands_over_the_command() {
        BrowserLinkException failure = assertThrows(BrowserLinkException.class,
                () -> BrowserLink.attach(9222, adapter, url -> {
                    throw new IllegalStateException("Connection refused");
                }, url -> new FakeCdpConnection(), said::add));

        assertEquals(BrowserLinkException.BROWSER_NOT_DETECTED, failure.code());
        assertTrue(failure.getMessage().contains("--remote-debugging-port=9222"),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("--user-data-dir"), failure.getMessage());
        assertTrue(failure.getMessage().contains("https://teams.microsoft.com"),
                failure.getMessage());
    }

    @Test
    @DisplayName("Navigateur relié mais pas d'onglet Teams : un AUTRE remède, qui dit quoi faire")
    void no_teams_tab_is_a_different_remedy() {
        BrowserLinkException failure = assertThrows(BrowserLinkException.class,
                () -> BrowserLink.attach(9222, adapter, fake(LIST_WITHOUT_TEAMS),
                        url -> new FakeCdpConnection(), said::add));

        assertEquals(BrowserLinkException.TEAMS_NOT_OPEN, failure.code());
        assertTrue(failure.getMessage().contains("Ouvrez https://teams.microsoft.com"),
                failure.getMessage());
    }

    @Test
    @DisplayName("Arrêté sur l'identification Microsoft : on demande de se connecter, une fois")
    void a_sign_in_page_asks_to_sign_in() {
        BrowserLinkException failure = assertThrows(BrowserLinkException.class,
                () -> BrowserLink.attach(9222, adapter, fake(LIST_ON_SIGN_IN),
                        url -> new FakeCdpConnection(), said::add));

        assertEquals(BrowserLinkException.NOT_SIGNED_IN, failure.code());
        assertTrue(failure.getMessage().contains("une seule fois"), failure.getMessage());
    }

    @Test
    @DisplayName("Le défilement est le seul geste demandé à la page, et il s'arrête tout seul")
    void scrolling_is_the_only_gesture_and_it_stops() {
        FakeCdpConnection browser = new FakeCdpConnection();
        BrowserLink link = BrowserLink.attach(9222, adapter, fake(LIST_WITH_TEAMS),
                url -> browser, said::add);

        assertEquals(5, link.scrollUp(5, millis -> { }));

        browser.scrollStopsMoving();
        assertEquals(1, link.scrollUp(5, millis -> { }),
                "la page ne remonte plus : insister ne ferait que perdre du temps");
        browser.sentCommands().forEach(method -> assertTrue(CdpCommands.isAllowed(method), method));
        link.close();
    }

    @Test
    @DisplayName("Le défilement est borné : on ne boucle jamais indéfiniment")
    void scrolling_is_bounded() {
        BrowserLink link = BrowserLink.attach(9222, adapter, fake(LIST_WITH_TEAMS),
                url -> new FakeCdpConnection(), said::add);

        assertEquals(BrowserLink.MAX_SCROLL_GESTURES, link.scrollUp(10_000, millis -> { }));
        link.close();
    }

    private Function<String, String> fake(String list) {
        Map<String, String> responses = Map.of(
                "http://127.0.0.1:9222/json/version", VERSION,
                "http://127.0.0.1:9222/json/list", list);
        return url -> {
            String body = responses.get(url);
            if (body == null) {
                throw new IllegalStateException("Adresse inattendue : " + url);
            }
            return body;
        };
    }
}
