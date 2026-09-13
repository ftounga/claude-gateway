package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-108 / SF-108-01 — <b>les gestes d'action et leurs gardes</b> (cadrage §4).
 *
 * <p>Ce que ce test tient, ce sont les gardes NON négociables : refus hors domaine, refus sur une
 * page d'identification, refus dans un champ mot de passe — chacune <b>avant</b> émission —, et la
 * trace de chaque geste sans le contenu saisi.</p>
 */
class PageActionsTest {

    private final List<PageActions.GestureRecord> journal = new ArrayList<>();
    private final Consumer<PageActions.GestureRecord> sink = journal::add;

    @Test
    @DisplayName("Refus hors domaine : une navigation hors liste ne part jamais")
    void navigation_off_domain_is_refused_before_emission() {
        StubConnection cdp = new StubConnection("https://teams.microsoft.com/v2/#/x");
        PageActions actions = new PageActions(cdp, noSleep(), sink);

        BrowserLinkException refused = assertThrows(BrowserLinkException.class,
                () -> actions.navigate("https://example.com/"));

        assertEquals(BrowserLinkException.DOMAIN_REFUSED, refused.code());
        assertFalse(cdp.sent.contains(CdpCommands.PAGE_NAVIGATE),
                "rien ne doit être émis quand la destination est refusée");
        assertTrue(journal.isEmpty(), "un geste refusé avant émission ne se journalise pas comme fait");
    }

    @Test
    @DisplayName("Un domaine Microsoft autorisé : la navigation part et est tracée")
    void navigation_to_allowed_domain_succeeds() {
        StubConnection cdp = new StubConnection("https://teams.microsoft.com/v2/#/x");
        PageActions actions = new PageActions(cdp, noSleep(), sink);

        String reached = actions.navigate("https://contoso.sharepoint.com/sites/IAM/Documents");

        assertTrue(cdp.sent.contains(CdpCommands.PAGE_NAVIGATE));
        assertTrue(reached.contains("sharepoint.com"));
        assertEquals(1, journal.size());
        assertEquals("navigate", journal.get(0).action());
        assertEquals("contoso.sharepoint.com", journal.get(0).domain());
    }

    @Test
    @DisplayName("Refus page d'identification : aucun clic sur une page de connexion")
    void gesture_on_sign_in_page_is_refused() {
        StubConnection cdp = new StubConnection("https://login.microsoftonline.com/common");
        cdp.clickable = true;
        PageActions actions = new PageActions(cdp, noSleep(), sink);

        BrowserLinkException refused = assertThrows(BrowserLinkException.class,
                () -> actions.click("button.download"));

        assertEquals(BrowserLinkException.SIGN_IN_REFUSED, refused.code());
        assertTrue(journal.isEmpty());
    }

    @Test
    @DisplayName("Refus champ mot de passe : aucune saisie, aucune lecture de la valeur")
    void typing_in_a_password_field_is_refused() {
        StubConnection cdp = new StubConnection("https://teams.microsoft.com/v2/#/x");
        cdp.activeFieldIsPassword = true;
        PageActions actions = new PageActions(cdp, noSleep(), sink);

        BrowserLinkException refused = assertThrows(BrowserLinkException.class,
                () -> actions.type("hunter2"));

        assertEquals(BrowserLinkException.PASSWORD_FIELD_REFUSED, refused.code());
        assertFalse(cdp.sent.contains(CdpCommands.INSERT_TEXT), "aucune saisie ne doit partir");
    }

    @Test
    @DisplayName("Saisie sur un champ ordinaire : elle part, et sa trace ne porte pas son contenu")
    void typing_is_traced_without_its_content() {
        StubConnection cdp = new StubConnection("https://teams.microsoft.com/v2/#/x");
        PageActions actions = new PageActions(cdp, noSleep(), sink);

        actions.type("Livrables");

        assertTrue(cdp.sent.contains(CdpCommands.INSERT_TEXT));
        assertEquals(1, journal.size());
        PageActions.GestureRecord record = journal.get(0);
        assertEquals("type", record.action());
        assertFalse(record.result().contains("Livrables"),
                "le journal ne porte jamais le contenu saisi : « " + record.result() + " »");
        assertTrue(record.result().contains("9 caractère"), record.result());
    }

    @Test
    @DisplayName("Un geste sur une page qui a quitté la liste (redirection) est refusé")
    void gesture_after_redirect_off_domain_is_refused() {
        StubConnection cdp = new StubConnection("https://evil.example.com/phishing");
        cdp.clickable = true;
        PageActions actions = new PageActions(cdp, noSleep(), sink);

        BrowserLinkException refused = assertThrows(BrowserLinkException.class,
                () -> actions.click("a"));

        assertEquals(BrowserLinkException.DOMAIN_REFUSED, refused.code());
    }

    @Test
    @DisplayName("Le téléchargement est dirigé vers un dossier ; l'adresse signée ne passe jamais ici")
    void download_directory_is_set_and_traced() {
        StubConnection cdp = new StubConnection("https://teams.microsoft.com/v2/#/x");
        PageActions actions = new PageActions(cdp, noSleep(), sink);

        actions.setDownloadDirectory("/home/user/.claude-runner/teams/work/rec");

        assertTrue(cdp.sent.contains(CdpCommands.SET_DOWNLOAD_BEHAVIOR));
        assertEquals("download_dir", journal.get(0).action());
    }

    private static BrowserLink.Sleeper noSleep() {
        return millis -> { };
    }

    /**
     * Un navigateur de papier réduit au strict nécessaire pour {@link PageActions} : il connaît son
     * adresse courante, dit si le champ actif est un mot de passe, et sait cliquer. Comme le vrai, il
     * refuse toute commande hors liste blanche.
     */
    private static final class StubConnection implements CdpConnection {

        private final ObjectMapper mapper = new ObjectMapper();
        private final List<String> sent = new ArrayList<>();
        private String url;
        private boolean clickable;
        private boolean activeFieldIsPassword;

        StubConnection(String url) {
            this.url = url;
        }

        @Override
        public JsonNode send(String method, ObjectNode params) {
            CdpCommands.assertAllowed(method);
            sent.add(method);
            if (CdpCommands.PAGE_NAVIGATE.equals(method)) {
                url = params.path("url").asText(url);
                return mapper.createObjectNode();
            }
            if (CdpCommands.EVALUATE.equals(method)) {
                return evaluate(params.path("expression").asText(""));
            }
            if (CdpCommands.GET_DOCUMENT.equals(method)) {
                ObjectNode result = mapper.createObjectNode();
                result.putObject("root").put("nodeId", 1);
                return result;
            }
            if (CdpCommands.QUERY_SELECTOR.equals(method)) {
                ObjectNode result = mapper.createObjectNode();
                result.put("nodeId", clickable ? 42 : 0);
                return result;
            }
            return mapper.createObjectNode();
        }

        private JsonNode evaluate(String expression) {
            ObjectNode result = mapper.createObjectNode();
            if (expression.contains("location.href")) {
                result.putObject("result").put("value", url);
            } else if (expression.contains("activeElement")) {
                result.putObject("result").put("value", activeFieldIsPassword);
            } else if (expression.contains(".click()")) {
                result.putObject("result").put("value", clickable);
            } else {
                result.putObject("result").putNull("value");
            }
            return result;
        }

        @Override
        public void onEvent(String method, Consumer<JsonNode> listener) {
            // Aucun événement dans ce stub.
        }

        @Override
        public boolean isOpen() {
            return true;
        }

        @Override
        public void close() {
            // Rien à fermer.
        }
    }
}
