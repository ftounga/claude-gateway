package fr.claudegateway.runner.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-132 / SF-132-01 — le collecteur d'événements de diagnostic : seuil, anneau borné, best-effort.
 */
class RunnerDiagTest {

    @BeforeEach
    @AfterEach
    void clean() {
        RunnerDiag.reset();
    }

    @Test
    @DisplayName("Le seuil INFO par défaut écarte le DEBUG et retient INFO/WARN/ERROR")
    void threshold_filters_debug_at_info() {
        RunnerDiag.debug("vigie", "tick", null, null);
        RunnerDiag.info("chrome", "chrome_state", null, null);
        RunnerDiag.warn("teams", "session_state", null, null);
        RunnerDiag.error("vigie", "tick_error", "Boom", null);

        RunnerDiag.Drained drained = RunnerDiag.drain(100);

        assertEquals(3, drained.events().size(), "le DEBUG ne doit pas passer à INFO");
        assertTrue(drained.events().stream().noneMatch(e -> e.level() == RunnerDiagLevel.DEBUG));
    }

    @Test
    @DisplayName("Passé en DEBUG (SF-132-05), le DEBUG passe ; retour à INFO le réécarte")
    void level_is_adjustable() {
        RunnerDiag.setLevel(RunnerDiagLevel.DEBUG);
        RunnerDiag.debug("vigie", "tick", null, null);
        assertEquals(1, RunnerDiag.drain(100).events().size());

        RunnerDiag.setLevel(RunnerDiagLevel.INFO);
        RunnerDiag.debug("vigie", "tick", null, null);
        assertEquals(0, RunnerDiag.drain(100).events().size());
    }

    @Test
    @DisplayName("L'anneau est borné : au-delà de la capacité, le plus ancien est écrasé et compté")
    void ring_is_bounded_and_counts_dropped() {
        int overflow = 30;
        for (int i = 0; i < RunnerDiag.CAPACITY + overflow; i++) {
            RunnerDiag.info("chrome", "chrome_state", null, Map.of("i", i));
        }

        RunnerDiag.Drained drained = RunnerDiag.drain(RunnerDiag.CAPACITY + overflow);

        assertEquals(RunnerDiag.CAPACITY, drained.events().size(), "l'anneau ne dépasse pas sa capacité");
        assertEquals(overflow, drained.dropped(), "les plus anciens écartés sont comptés");
        // Le plus ancien retenu est le (overflow)-ième émis : les tout premiers ont été écrasés.
        assertEquals(overflow, drained.events().get(0).fields().get("i"));
    }

    @Test
    @DisplayName("event(...) n'échoue jamais, même sur des entrées aberrantes (best-effort)")
    void event_never_throws() {
        Map<String, Object> reentrant = new HashMap<>();
        // Une valeur non scalaire (carte) doit être écartée sans lever.
        reentrant.put("nested", Map.of("secret", "value"));
        RunnerDiag.info("chrome", "chrome_state", null, reentrant);
        RunnerDiag.event(null, "chrome", "x", null, null); // niveau null : ignoré, pas d'erreur
        RunnerDiag.info(null, null, null, null); // cat/code nuls : ignoré

        RunnerDiag.Drained drained = RunnerDiag.drain(100);
        assertEquals(1, drained.events().size());
        assertNull(drained.events().get(0).fields().get("nested"),
                "un champ non scalaire ne doit jamais survivre");
    }

    @Test
    @DisplayName("Niveau temporaire (SF-132-05) : DEBUG passe, puis retour AUTO à INFO à l'échéance")
    void temporary_level_reverts_to_info() {
        long[] now = { 1_000_000L };
        RunnerDiag.setClock(() -> now[0]);

        RunnerDiag.setTemporaryLevel(RunnerDiagLevel.DEBUG, 600); // 10 min
        assertEquals(RunnerDiagLevel.DEBUG, RunnerDiag.level());
        RunnerDiag.debug("vigie", "tick", null, null);
        assertEquals(1, RunnerDiag.drain(100).events().size(), "le DEBUG passe pendant la fenêtre");

        // L'échéance est franchie : le retour à INFO est constaté paresseusement.
        now[0] += 600_000L + 1;
        assertEquals(RunnerDiagLevel.INFO, RunnerDiag.level(), "retour auto à INFO");
        RunnerDiag.debug("vigie", "tick", null, null);
        assertEquals(0, RunnerDiag.drain(100).events().size(), "le DEBUG est de nouveau écarté");
    }

    @Test
    @DisplayName("setLevel permanent annule un réglage temporaire en cours")
    void permanent_level_cancels_temporary() {
        long[] now = { 5_000L };
        RunnerDiag.setClock(() -> now[0]);
        RunnerDiag.setTemporaryLevel(RunnerDiagLevel.DEBUG, 600);

        RunnerDiag.setLevel(RunnerDiagLevel.WARN);
        now[0] += 10_000_000L; // bien au-delà de l'ancienne échéance

        assertEquals(RunnerDiagLevel.WARN, RunnerDiag.level(),
                "un réglage permanent ne doit pas retomber à INFO");
    }

    @Test
    @DisplayName("Un TTL nul règle le niveau sans expiration")
    void temporary_level_without_ttl_is_permanent() {
        long[] now = { 0L };
        RunnerDiag.setClock(() -> now[0]);
        RunnerDiag.setTemporaryLevel(RunnerDiagLevel.DEBUG, 0);
        now[0] += 10_000_000L;
        assertEquals(RunnerDiagLevel.DEBUG, RunnerDiag.level());
    }

    @Test
    @DisplayName("isEmpty reflète l'état ; un drainage vide l'anneau")
    void is_empty_and_drain_clears() {
        assertTrue(RunnerDiag.isEmpty());
        RunnerDiag.info("chrome", "chrome_state", null, null);
        assertFalse(RunnerDiag.isEmpty());
        RunnerDiag.drain(100);
        assertTrue(RunnerDiag.isEmpty());
    }
}
