package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * F-108 / SF-108-01 — <b>la liste close des domaines Microsoft</b> (cadrage §4.1 et §4.2).
 *
 * <p>Les gestes d'action sont bornés à cette liste ; c'est une décision de sécurité, et une garde
 * qui se tient par un test, pas par la vigilance.</p>
 */
class MicrosoftDomainsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://teams.microsoft.com/v2/#/conversations/19:x@thread.v2",
            "https://teams.cloud.microsoft/",
            "https://teams.live.com/",
            "https://contoso.sharepoint.com/sites/IAM/Documents",
            "https://onedrive.live.com/?id=root",
            "https://word.office.com/doc",
            "https://excel.officeapps.live.com/x",
            "https://foo.cloud.microsoft/bar"})
    @DisplayName("Chaque domaine de la liste close est autorisé, jokers compris")
    void allowed_domains_are_recognized(String url) {
        assertTrue(MicrosoftDomains.isAllowed(url), url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://example.com/",
            "https://sharepoint.com.evil.com/",
            "https://notsharepoint.com/",
            "https://google.com/",
            "http://127.0.0.1:9222/",
            "https://teams.microsoft.com.evil.com/"})
    @DisplayName("Tout hôte hors liste est refusé — le refus est le défaut")
    void everything_else_is_refused(String url) {
        assertFalse(MicrosoftDomains.isAllowed(url), url);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://login.microsoftonline.com/common/oauth2/authorize",
            "https://login.live.com/",
            "https://login.microsoft.com/"})
    @DisplayName("Les hôtes d'identification sont reconnus, et jamais autorisés pour un geste")
    void sign_in_hosts_are_never_allowed(String url) {
        assertTrue(MicrosoftDomains.isSignIn(url), url);
        assertFalse(MicrosoftDomains.isAllowed(url), url);
    }

    @Test
    @DisplayName("Une adresse vide ou illisible n'est jamais autorisée")
    void blank_is_refused() {
        assertFalse(MicrosoftDomains.isAllowed(null));
        assertFalse(MicrosoftDomains.isAllowed(""));
        assertFalse(MicrosoftDomains.isAllowed("   "));
        assertFalse(MicrosoftDomains.isSignIn(null));
    }

    @Test
    @DisplayName("L'hôte est extrait en minuscule, sans port ni chemin")
    void host_is_normalized() {
        assertEquals("teams.microsoft.com",
                MicrosoftDomains.hostOf("HTTPS://Teams.Microsoft.Com:443/v2/#/x"));
        assertEquals("contoso.sharepoint.com",
                MicrosoftDomains.hostOf("https://user@contoso.sharepoint.com/path?q=1"));
    }
}
