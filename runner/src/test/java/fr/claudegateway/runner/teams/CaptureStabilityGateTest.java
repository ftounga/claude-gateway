package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le portillon de stabilité de la capture (F-128 / SF-128-14) — logique pure de détection
 * « in-call ». On éprouve que le minuteur de silence se ré-arme à chaque navigation, que la
 * stabilité se déclare après {@code quietMillis} sans navigation, et que le plafond force un
 * démarrage best-effort. Le comportement navigateur réel reste « À VALIDER SUR CALL RÉEL ».
 */
class CaptureStabilityGateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("sans navigation : la stabilité est atteinte après quietMillis de silence")
    void stableAfterQuietPeriodWithoutNavigation() {
        // quiet=500, poll=100 → 5 créneaux de silence suffisent.
        CaptureStabilityGate gate = new CaptureStabilityGate(500, 5_000, 100);

        CaptureStabilityGate.State state = null;
        int ticks = 0;
        for (int i = 0; i < 4; i++) {
            state = gate.tick();
            ticks++;
            assertEquals(CaptureStabilityGate.State.WAITING, state, "silence encore trop court");
        }
        state = gate.tick();
        ticks++;
        assertEquals(CaptureStabilityGate.State.STABLE, state, "5 créneaux de silence → stable");
        assertEquals(5, ticks);
        assertEquals(0, gate.navigationsObserved(), "aucune navigation observée");
    }

    @Test
    @DisplayName("une navigation ré-arme le minuteur de silence (le silence repart de zéro)")
    void navigationReArmsTheQuietTimer() {
        CaptureStabilityGate gate = new CaptureStabilityGate(500, 10_000, 100);

        for (int i = 0; i < 4; i++) {
            assertEquals(CaptureStabilityGate.State.WAITING, gate.tick());
        }
        // Une navigation juste avant d'atteindre le silence : le minuteur DOIT repartir de zéro.
        gate.onNavigation();
        assertEquals(CaptureStabilityGate.State.WAITING, gate.tick(),
                "après une navigation, le silence repart de zéro");

        // Il faut de nouveau 5 créneaux pleins de silence pour se stabiliser.
        for (int i = 0; i < 4; i++) {
            assertEquals(CaptureStabilityGate.State.WAITING, gate.tick());
        }
        assertEquals(CaptureStabilityGate.State.STABLE, gate.tick(), "silence complet → stable");
        assertEquals(1, gate.navigationsObserved());
    }

    @Test
    @DisplayName("navigations continues → jamais stable → plafond atteint (démarrage best-effort)")
    void continuousNavigationHitsTheCap() {
        // quiet=1000 (10 créneaux), cap=500 (5 créneaux) : le plafond arrive avant tout silence.
        CaptureStabilityGate gate = new CaptureStabilityGate(1_000, 500, 100);

        CaptureStabilityGate.State state = null;
        for (int i = 0; i < 5; i++) {
            gate.onNavigation(); // une navigation à chaque créneau : jamais de silence
            state = gate.tick();
        }
        assertEquals(CaptureStabilityGate.State.CAP_REACHED, state,
                "sans jamais se stabiliser, on démarre au plafond");
        assertEquals(5, gate.navigationsObserved());
    }

    @Test
    @DisplayName("un sous-cadre (frame.parentId) n'est pas une navigation : seul le cadre principal ré-arme")
    void subFrameNavigationDoesNotReArm() {
        CaptureStabilityGate gate = new CaptureStabilityGate(300, 10_000, 100);

        ObjectNode subFrame = mapper.createObjectNode();
        subFrame.putObject("frame").put("parentId", "parent-123");
        gate.onFrameNavigated(subFrame); // ignoré

        assertEquals(CaptureStabilityGate.State.WAITING, gate.tick());
        assertEquals(CaptureStabilityGate.State.WAITING, gate.tick());
        assertEquals(CaptureStabilityGate.State.STABLE, gate.tick(),
                "un sous-cadre ne ré-arme pas : le silence n'a pas été interrompu");
        assertEquals(0, gate.navigationsObserved(), "une iframe tierce ne compte pas");

        // Sur un portillon neuf (non stabilisé), une navigation du cadre PRINCIPAL, elle, compte.
        CaptureStabilityGate fresh = new CaptureStabilityGate(300, 10_000, 100);
        ObjectNode mainFrame = mapper.createObjectNode();
        mainFrame.putObject("frame"); // pas de parentId : cadre principal
        fresh.onFrameNavigated(mainFrame);
        assertEquals(1, fresh.navigationsObserved(), "le cadre principal compte");
    }

    @Test
    @DisplayName("awaitStable : page déjà stable (aucune navigation) → STABLE via le Sleeper")
    void awaitStableReturnsStableWhenQuiet() {
        CaptureStabilityGate gate = new CaptureStabilityGate(500, 5_000, 100);
        int[] sleeps = { 0 };

        CaptureStabilityGate.State state = gate.awaitStable(millis -> sleeps[0]++);

        assertEquals(CaptureStabilityGate.State.STABLE, state);
        assertEquals(5, sleeps[0], "5 créneaux de scrutation pour 500ms de silence à 100ms");
    }

    @Test
    @DisplayName("awaitStable : navigation à chaque créneau → CAP_REACHED (terminaison garantie)")
    void awaitStableHitsCapUnderContinuousNavigation() {
        CaptureStabilityGate gate = new CaptureStabilityGate(1_000, 500, 100);

        // Le Sleeper simule une navigation à chaque créneau : jamais de silence, mais le plafond borne.
        CaptureStabilityGate.State state = gate.awaitStable(millis -> gate.onNavigation());

        assertEquals(CaptureStabilityGate.State.CAP_REACHED, state);
        assertTrue(gate.navigationsObserved() >= 5, "le plafond a bien borné une attente continue");
    }

    @Test
    @DisplayName("durées non positives refusées à la construction")
    void rejectsNonPositiveDurations() {
        assertThrows(IllegalArgumentException.class, () -> new CaptureStabilityGate(0, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new CaptureStabilityGate(10, 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new CaptureStabilityGate(10, 10, 0));
    }
}
