package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-122 / SF-122-03 — l'expiration de la session Teams se voit, et la reconnexion se demande. */
class TeamsSessionWatchTest {

    private static final String SIGN_IN = "https://login.microsoftonline.com/common/oauth2/authorize";
    private static final String TEAMS_API = "https://teams.microsoft.com/api/csa/amer/conversations";
    private static final String NOISE = "https://example.com/whatever";

    @Test
    @DisplayName("une redirection vers le login Microsoft demande une reconnexion")
    void signInRedirectRequiresRelogin() {
        TeamsSessionWatch watch = new TeamsSessionWatch(null);

        watch.observe(SIGN_IN, 200);

        assertEquals(TeamsSessionState.RELOGIN_REQUIRED, watch.state());
        assertTrue(watch.reloginRequired());
    }

    @Test
    @DisplayName("un 401/403 sur une réponse Microsoft demande une reconnexion")
    void unauthorizedRequiresRelogin() {
        assertEquals(TeamsSessionState.RELOGIN_REQUIRED, observeOne(TEAMS_API, 401));
        assertEquals(TeamsSessionState.RELOGIN_REQUIRED, observeOne(TEAMS_API, 403));
    }

    @Test
    @DisplayName("une réponse Microsoft 2xx marque la session connectée")
    void successMarksConnected() {
        assertEquals(TeamsSessionState.CONNECTED, observeOne(TEAMS_API, 200));
    }

    @Test
    @DisplayName("le bruit hors des domaines Microsoft ne change pas l'état")
    void noiseIsIgnored() {
        TeamsSessionWatch watch = new TeamsSessionWatch(null);
        watch.observe(TEAMS_API, 200);

        watch.observe(NOISE, 401);

        assertEquals(TeamsSessionState.CONNECTED, watch.state());
    }

    @Test
    @DisplayName("la notification n'est émise qu'à la bascule, pas à chaque observation")
    void notifiesOncePerTransition() {
        List<String> said = new ArrayList<>();
        TeamsSessionWatch watch = new TeamsSessionWatch(said::add);

        watch.observe(TEAMS_API, 401);
        watch.observe(TEAMS_API, 401);
        watch.observe(SIGN_IN, 200);

        assertEquals(1, said.size(), said.toString());
    }

    @Test
    @DisplayName("relogin puis reconnexion : les deux coutures se déclenchent aux bonnes bascules")
    void reloginThenReconnectTriggersSeams() {
        int[] reloginCalls = {0};
        int[] reconnectCalls = {0};
        List<String> said = new ArrayList<>();
        TeamsSessionWatch watch = new TeamsSessionWatch(said::add,
                () -> reloginCalls[0]++, () -> reconnectCalls[0]++);

        watch.observe(TEAMS_API, 200);   // CONNECTED, aucune notification (pas de bascule depuis relogin)
        watch.observe(TEAMS_API, 401);   // -> RELOGIN_REQUIRED : rappel de fenêtre
        watch.observe(TEAMS_API, 200);   // -> CONNECTED : remise en arrière-plan

        assertEquals(1, reloginCalls[0]);
        assertEquals(1, reconnectCalls[0]);
        assertEquals(TeamsSessionState.CONNECTED, watch.state());
        assertFalse(watch.reloginRequired());
        assertEquals(2, said.size(), said.toString()); // expiration + rétablissement
    }

    private static TeamsSessionState observeOne(String url, int status) {
        TeamsSessionWatch watch = new TeamsSessionWatch(null);
        watch.observe(url, status);
        return watch.state();
    }
}
