package fr.claudegateway.runner.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/** F-111 / SF-111-02 — le code de sortie dit au lanceur quoi faire (cadrage §2). */
class LauncherPolicyTest {

    private static final Instant T0 = Instant.parse("2026-09-13T10:00:00Z");

    @Test
    void soixanteQuinzeDemarreLaVersionMiseAJour() {
        assertEquals(LauncherPolicy.Action.UPDATE, new LauncherPolicy().onExit(75, T0));
    }

    @Test
    void lesCodesDArretExistantsArretentLeLanceur() {
        LauncherPolicy policy = new LauncherPolicy();
        for (int code : new int[] { 0, 2, 3, 4, 5, 6 }) {
            assertEquals(LauncherPolicy.Action.STOP, policy.onExit(code, T0), "code " + code);
        }
        assertEquals(0, policy.recentCrashes(), "un arrêt n'est pas un plantage");
    }

    @Test
    void unPlantageEstRelanceTroisFoisAuPlusEnCinqMinutes() {
        LauncherPolicy policy = new LauncherPolicy();
        assertEquals(LauncherPolicy.Action.RESTART, policy.onExit(1, T0));
        assertEquals(LauncherPolicy.Action.RESTART, policy.onExit(137, T0.plusSeconds(30)));
        assertEquals(LauncherPolicy.Action.RESTART, policy.onExit(1, T0.plusSeconds(60)));
        assertEquals(LauncherPolicy.Action.GIVE_UP, policy.onExit(1, T0.plusSeconds(90)),
                "le quatrième plantage en cinq minutes arrête le lanceur");
    }

    @Test
    void laFenetreGlisse() {
        LauncherPolicy policy = new LauncherPolicy();
        policy.onExit(1, T0);
        policy.onExit(1, T0.plus(Duration.ofMinutes(2)));
        policy.onExit(1, T0.plus(Duration.ofMinutes(4)));
        assertEquals(LauncherPolicy.Action.RESTART, policy.onExit(1, T0.plus(Duration.ofMinutes(6))),
                "le premier plantage est sorti de la fenêtre");
        assertEquals(3, policy.recentCrashes());
    }

    @Test
    void uneNouvelleVersionRepartDUnCompteVierge() {
        LauncherPolicy policy = new LauncherPolicy();
        policy.onExit(1, T0);
        policy.onExit(1, T0);
        policy.onExit(1, T0);
        policy.resetCrashes();
        assertEquals(LauncherPolicy.Action.RESTART, policy.onExit(1, T0));
    }
}
