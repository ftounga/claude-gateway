package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-122 / SF-122-06 — le jugement de la sonde réelle : ce que la sonde de santé a vu devient un état
 * de session (SF-122-03) et un test de lecture, sans navigateur.
 */
class VigieSondeTest {

    private static final String TEAMS_URL = "https://teams.microsoft.com";
    private static final String SIGNIN_URL = "https://login.microsoftonline.com/common/oauth2";

    @Test
    @DisplayName("Teams a répondu et l'adaptateur a lu : session connectée, test de lecture réussi")
    void connected_when_linked_and_conclusive() {
        TeamsSessionWatch watch = new TeamsSessionWatch(msg -> { });

        VigieLoop.Reading reading = VigieSonde.judge(
                new TeamsProbeResult(TeamsLinkState.LINKED, TeamsHealth.full(1), 1, "chrome", ""),
                TEAMS_URL, watch);

        assertEquals(TeamsSessionState.CONNECTED, reading.sessionState());
        assertTrue(reading.readTest());
    }

    @Test
    @DisplayName("L'onglet est resté sur une page d'identification : reconnexion requise, couture appelée")
    void relogin_when_sign_in_tab() {
        AtomicInteger revealed = new AtomicInteger();
        TeamsSessionWatch watch = new TeamsSessionWatch(msg -> { }, revealed::incrementAndGet, null);

        VigieLoop.Reading reading = VigieSonde.judge(
                new TeamsProbeResult(TeamsLinkState.LINKED, TeamsHealth.full(0), 1, "chrome", ""),
                SIGNIN_URL, watch);

        assertEquals(TeamsSessionState.RELOGIN_REQUIRED, reading.sessionState());
        assertFalse(reading.readTest());
        assertEquals(1, revealed.get(), "la fenêtre managée doit surgir à la bascule (SF-122-03)");
    }

    @Test
    @DisplayName("Aucune réponse Teams observée : rien ne bascule, test de lecture en attente")
    void pending_when_nothing_observed() {
        TeamsSessionWatch watch = new TeamsSessionWatch(msg -> { });

        VigieLoop.Reading reading = VigieSonde.judge(
                new TeamsProbeResult(TeamsLinkState.LINKED, TeamsHealth.full(0), 0, "chrome", ""),
                TEAMS_URL, watch);

        assertEquals(TeamsSessionState.UNKNOWN, reading.sessionState());
        assertFalse(reading.readTest());
    }

    @Test
    @DisplayName("Une liaison NOT_SIGNED_IN fait basculer la veille en reconnexion requise")
    void failure_not_signed_in_triggers_relogin() {
        AtomicInteger revealed = new AtomicInteger();
        TeamsSessionWatch watch = new TeamsSessionWatch(msg -> { }, revealed::incrementAndGet, null);

        VigieSonde.feedFromFailure(BrowserLinkException.NOT_SIGNED_IN, watch);

        assertEquals(TeamsSessionState.RELOGIN_REQUIRED, watch.state());
        assertEquals(1, revealed.get());
    }

    @Test
    @DisplayName("Une liaison « Teams non ouvert » ne prétend pas connaître l'état de session")
    void failure_teams_not_open_leaves_state_unknown() {
        TeamsSessionWatch watch = new TeamsSessionWatch(msg -> { });

        VigieSonde.feedFromFailure(BrowserLinkException.TEAMS_NOT_OPEN, watch);

        assertEquals(TeamsSessionState.UNKNOWN, watch.state());
    }
}
