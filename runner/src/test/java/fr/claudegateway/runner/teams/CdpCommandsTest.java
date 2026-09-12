package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * F-87 / SF-87-02 — <b>le verrou de sécurité</b> : la liste close des commandes de débogage.
 *
 * <p>Le protocole donne accès aux cookies et au stockage de la session. L'avantage de l'approche
 * navigateur — rien de ce qui authentifie l'utilisateur ne remonte — tient à ce que ces commandes
 * <b>ne puissent pas</b> être émises, et pas à ce que personne n'y pense.</p>
 */
class CdpCommandsTest {

    @Test
    @DisplayName("Cinq commandes, et cinq seulement")
    void exactly_five_commands() {
        assertEquals(5, CdpCommands.allowed().size(), CdpCommands.allowed().toString());
        CdpCommands.allowed().forEach(method -> assertDoesNotThrow(
                () -> CdpCommands.assertAllowed(method), method));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Network.getCookies", "Network.getAllCookies", "Network.setCookie",
            "Storage.getCookies", "Network.clearBrowserCookies"})
    @DisplayName("Aucune commande ne peut atteindre les cookies de la session")
    void cookies_are_out_of_reach(String method) {
        BrowserLinkException refused = assertThrows(BrowserLinkException.class,
                () -> CdpCommands.assertAllowed(method));

        assertEquals(BrowserLinkException.COMMAND_REFUSED, refused.code());
        assertTrue(refused.getMessage().contains("cookies"), refused.getMessage());
        assertFalse(CdpCommands.isAllowed(method));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Page.navigate", "Input.dispatchKeyEvent", "Input.dispatchMouseEvent"})
    @DisplayName("On lit, on ne pilote pas : naviguer et taper au clavier sont refusés")
    void driving_the_page_is_refused(String method) {
        BrowserLinkException refused = assertThrows(BrowserLinkException.class,
                () -> CdpCommands.assertAllowed(method));
        assertTrue(refused.getMessage().contains("le volet Teams lit, il ne pilote pas"),
                refused.getMessage());
    }

    @Test
    @DisplayName("Le refus dit ce qui était autorisé : il est réparable")
    void a_refusal_says_what_is_allowed() {
        BrowserLinkException refused = assertThrows(BrowserLinkException.class,
                () -> CdpCommands.assertAllowed("DOM.getDocument"));

        assertTrue(refused.getMessage().contains("Network.getResponseBody"));
        assertTrue(refused.getMessage().contains("DOM.getDocument"));
    }

    @Test
    @DisplayName("Une commande absente est refusée comme les autres")
    void a_missing_command_is_refused() {
        assertThrows(BrowserLinkException.class, () -> CdpCommands.assertAllowed(null));
        assertThrows(BrowserLinkException.class, () -> CdpCommands.assertAllowed(""));
    }
}
