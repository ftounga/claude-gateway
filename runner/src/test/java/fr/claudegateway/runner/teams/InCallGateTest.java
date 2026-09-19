package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le portillon d'entrée en réunion (F-128 / SF-128-16) — logique pure de détection du <b>vrai</b>
 * in-call. On éprouve que le signal réel fait entrer en réunion, que son absence fait attendre, et que
 * le plafond borne l'attente (best-effort). Le signal navigateur réel reste « À VALIDER SUR CALL RÉEL ».
 */
class InCallGateTest {

    @Test
    @DisplayName("signal présent : entrée en réunion immédiate (IN_CALL)")
    void signalPresentEntersInCall() {
        InCallGate gate = new InCallGate(5_000, 500);

        assertEquals(InCallGate.State.IN_CALL, gate.tick(true), "un signal réel → in-call tout de suite");
        assertEquals(1, gate.probesObserved());
    }

    @Test
    @DisplayName("signal absent : on attend (WAITING) tant que le plafond n'est pas atteint")
    void signalAbsentWaitsUntilCap() {
        // cap=1000 (2 créneaux à 500), poll=500.
        InCallGate gate = new InCallGate(1_000, 500);

        assertEquals(InCallGate.State.WAITING, gate.tick(false), "pré-join : pas de signal → on attend");
        assertEquals(InCallGate.State.CAP_REACHED, gate.tick(false), "plafond atteint → best-effort");
        assertEquals(2, gate.probesObserved());
    }

    @Test
    @DisplayName("signal absent puis présent avant le plafond → IN_CALL")
    void signalArrivesBeforeCap() {
        InCallGate gate = new InCallGate(10_000, 500);

        assertEquals(InCallGate.State.WAITING, gate.tick(false));
        assertEquals(InCallGate.State.WAITING, gate.tick(false));
        assertEquals(InCallGate.State.IN_CALL, gate.tick(true), "le signal arrive → in-call");
    }

    @Test
    @DisplayName("awaitInCall : le signal devient présent après quelques créneaux → IN_CALL")
    void awaitInCallReturnsInCallWhenSignalArrives() {
        InCallGate gate = new InCallGate(10_000, 500);
        int[] polls = { 0 };

        // Le signal n'est vrai qu'à partir de la 3ᵉ sonde (transition pré-join → in-call modélisée).
        InCallGate.State state = gate.awaitInCall(millis -> { }, () -> ++polls[0] >= 3);

        assertEquals(InCallGate.State.IN_CALL, state);
        assertEquals(3, polls[0], "on entre en réunion dès que le signal réel apparaît");
    }

    @Test
    @DisplayName("awaitInCall : signal jamais présent → CAP_REACHED (terminaison garantie)")
    void awaitInCallHitsCapWhenNeverInCall() {
        // cap=2000 (4 créneaux à 500) : le plafond borne une attente sans signal.
        InCallGate gate = new InCallGate(2_000, 500);

        InCallGate.State state = gate.awaitInCall(millis -> { }, () -> false);

        assertEquals(InCallGate.State.CAP_REACHED, state);
        assertTrue(gate.probesObserved() >= 4, "le plafond a bien borné l'attente sans signal");
    }

    @Test
    @DisplayName("durées non positives refusées à la construction")
    void rejectsNonPositiveDurations() {
        assertThrows(IllegalArgumentException.class, () -> new InCallGate(0, 10));
        assertThrows(IllegalArgumentException.class, () -> new InCallGate(10, 0));
    }
}
