package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-87 / SF-87-02 — où écouter le navigateur, et pourquoi jamais ailleurs que sur cette machine. */
class BrowserPortTest {

    @Test
    @DisplayName("Argument, puis environnement, puis défaut")
    void resolution_order() {
        Map<String, String> env = Map.of(BrowserPort.ENV, "9444");

        assertEquals(9333, BrowserPort.resolve("9333", env::get));
        assertEquals(9444, BrowserPort.resolve(null, env::get));
        assertEquals(BrowserPort.DEFAULT_PORT, BrowserPort.resolve(null, name -> null));
        assertEquals(9222, BrowserPort.DEFAULT_PORT);
    }

    @Test
    @DisplayName("Une valeur illisible retombe sur le défaut sans faire échouer le runner")
    void unusable_values_fall_back() {
        assertEquals(BrowserPort.DEFAULT_PORT, BrowserPort.resolve("pas-un-port", name -> null));
        assertEquals(BrowserPort.DEFAULT_PORT, BrowserPort.resolve("80", name -> null));
        assertEquals(BrowserPort.DEFAULT_PORT, BrowserPort.resolve("70000", name -> null));
        assertFalse(BrowserPort.isUsable("pas-un-port"));
        assertTrue(BrowserPort.isUsable("9222"));
        assertTrue(BrowserPort.isUsable(null));
    }

    @Test
    @DisplayName("On ne se rattache JAMAIS à un navigateur qui n'est pas sur cette machine")
    void never_attaches_to_a_remote_browser() {
        BrowserLinkException refused = assertThrows(BrowserLinkException.class,
                () -> BrowserPort.discoveryUrl("192.168.1.20", 9222, "/json/list"));

        assertEquals(BrowserLinkException.REMOTE_BROWSER_REFUSED, refused.code());
        assertTrue(refused.getMessage().contains("127.0.0.1"));
    }

    @Test
    @DisplayName("L'adresse de découverte est toujours celle de la boucle locale")
    void discovery_url_is_always_loopback() {
        assertEquals("http://127.0.0.1:9222/json/list",
                BrowserPort.discoveryUrl("localhost", 9222, "/json/list"));
        assertEquals("http://127.0.0.1:9333/json/version",
                BrowserPort.discoveryUrl(null, 9333, "/json/version"));
    }
}
