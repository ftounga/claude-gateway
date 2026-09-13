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
    @DisplayName("La liste blanche : les cinq de lecture, plus les gestes d'action de F-108")
    void the_whitelist_covers_reading_and_action() {
        // SF-87-02 a posé cinq commandes de lecture ; SF-108-01 y ajoute les gestes d'action,
        // strictement nécessaires (cadrage §4) — ni plus, ni moins. Ce test GARDE la liste : il est
        // mis à jour quand elle change à dessein, jamais supprimé.
        assertEquals(java.util.List.of("Browser.getVersion", "Page.enable", "Network.enable",
                "Network.getResponseBody", "Runtime.evaluate", "Page.navigate",
                "Input.dispatchMouseEvent", "Input.dispatchKeyEvent", "Input.insertText",
                "DOM.getDocument", "DOM.querySelector", "DOM.setFileInputFiles",
                "Browser.setDownloadBehavior", "Target.setAutoAttach"),
                CdpCommands.allowed(), CdpCommands.allowed().toString());
        CdpCommands.allowed().forEach(method -> assertDoesNotThrow(
                () -> CdpCommands.assertAllowed(method), method));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Network.getCookies", "Network.getAllCookies", "Network.setCookie",
            "Storage.getCookies", "Network.clearBrowserCookies"})
    @DisplayName("Aucune commande ne peut atteindre les cookies ou le stockage de la session")
    void cookies_and_storage_stay_out_of_reach(String method) {
        BrowserLinkException refused = assertThrows(BrowserLinkException.class,
                () -> CdpCommands.assertAllowed(method));

        assertEquals(BrowserLinkException.COMMAND_REFUSED, refused.code());
        assertTrue(refused.getMessage().contains("cookies") || refused.getMessage().contains("stockage"),
                refused.getMessage());
        assertFalse(CdpCommands.isAllowed(method));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Page.navigate", "Input.dispatchKeyEvent", "Input.dispatchMouseEvent",
            "Input.insertText", "DOM.setFileInputFiles", "Browser.setDownloadBehavior",
            "Target.setAutoAttach"})
    @DisplayName("F-108 : les gestes d'action sont dans la liste blanche (gardés par domaine ailleurs)")
    void action_gestures_are_allowed(String method) {
        assertTrue(CdpCommands.isAllowed(method), method);
        assertDoesNotThrow(() -> CdpCommands.assertAllowed(method));
    }

    @Test
    @DisplayName("Le refus dit ce qui était autorisé : il est réparable")
    void a_refusal_says_what_is_allowed() {
        // Une commande hors liste, qui n'a jamais été un geste du volet.
        BrowserLinkException refused = assertThrows(BrowserLinkException.class,
                () -> CdpCommands.assertAllowed("DOM.setAttributeValue"));

        assertTrue(refused.getMessage().contains("Network.getResponseBody"));
        assertTrue(refused.getMessage().contains("DOM.setAttributeValue"));
    }

    @Test
    @DisplayName("Une commande absente est refusée comme les autres")
    void a_missing_command_is_refused() {
        assertThrows(BrowserLinkException.class, () -> CdpCommands.assertAllowed(null));
        assertThrows(BrowserLinkException.class, () -> CdpCommands.assertAllowed(""));
    }
}
