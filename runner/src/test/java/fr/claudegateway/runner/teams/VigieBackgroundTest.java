package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-122 / SF-122-03 — l'assemblage du rapport que la Vigie fait remonter en tournant seule. */
class VigieBackgroundTest {

    @Test
    @DisplayName("tout prêt : Chrome joignable, Teams connecté, lecture vérifiée")
    void everythingReady() {
        VigieReadinessReport report =
                VigieBackground.assemble(true, TeamsSessionState.CONNECTED, true);

        assertTrue(report.chromeReachable());
        assertTrue(report.teamsConnected());
        assertFalse(report.teamsSignInRequired());
        assertTrue(report.teamsReadTest());
    }

    @Test
    @DisplayName("session expirée : reconnexion requise, Teams non connecté")
    void reloginRequired() {
        VigieReadinessReport report =
                VigieBackground.assemble(true, TeamsSessionState.RELOGIN_REQUIRED, false);

        assertTrue(report.teamsSignInRequired());
        assertFalse(report.teamsConnected());
        assertTrue(report.detail().toLowerCase().contains("reconnexion"));
    }

    @Test
    @DisplayName("Chrome non joignable : le rapport le dit d'abord")
    void chromeUnreachable() {
        VigieReadinessReport report =
                VigieBackground.assemble(false, TeamsSessionState.CONNECTED, true);

        assertFalse(report.chromeReachable());
        assertTrue(report.detail().toLowerCase().contains("chrome"));
    }

    @Test
    @DisplayName("Teams connecté mais test de lecture en attente")
    void readTestPending() {
        VigieReadinessReport report =
                VigieBackground.assemble(true, TeamsSessionState.CONNECTED, false);

        assertTrue(report.teamsConnected());
        assertFalse(report.teamsReadTest());
    }
}
