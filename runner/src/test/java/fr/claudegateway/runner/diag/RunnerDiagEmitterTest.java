package fr.claudegateway.runner.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-132 / SF-132-01 — l'émetteur batché : une trame {@code runner_diag} valide, batchée, best-effort,
 * et jamais émise à vide (pas de spam).
 */
class RunnerDiagEmitterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    @AfterEach
    void clean() {
        RunnerDiag.reset();
    }

    @Test
    @DisplayName("flush draine plusieurs événements en UNE trame runner_diag valide (batch)")
    void flush_batches_into_one_frame() throws Exception {
        List<String> sent = new ArrayList<>();
        RunnerDiagEmitter emitter = new RunnerDiagEmitter(sent::add);
        RunnerDiag.info("chrome", "chrome_state", null, Map.of("state", "REACHABLE", "port", 9222));
        RunnerDiag.warn("teams", "session_state", null, Map.of("session", "RELOGIN_REQUIRED"));

        emitter.flush();

        assertEquals(1, sent.size(), "un seul lot pour deux événements");
        JsonNode frame = mapper.readTree(sent.get(0));
        assertEquals("runner_diag", frame.path("type").asText());
        assertEquals(2, frame.path("events").size());
        assertEquals("chrome", frame.path("events").get(0).path("cat").asText());
        assertEquals(9222, frame.path("events").get(0).path("fields").path("port").asInt());
    }

    @Test
    @DisplayName("Rien à drainer : aucune trame émise (pas de spam)")
    void nothing_to_drain_emits_nothing() {
        List<String> sent = new ArrayList<>();
        new RunnerDiagEmitter(sent::add).flush();
        assertTrue(sent.isEmpty());
    }

    @Test
    @DisplayName("Un send qui lève n'interrompt jamais le drainage (best-effort)")
    void send_failure_is_not_fatal() {
        RunnerDiagEmitter emitter = new RunnerDiagEmitter(frame -> {
            throw new IllegalStateException("socket fermée");
        });
        RunnerDiag.info("chrome", "chrome_state", null, null);
        emitter.flush(); // ne doit rien laisser remonter
    }

    @Test
    @DisplayName("Le débordement de l'anneau est reporté dans la trame (champ dropped)")
    void dropped_is_reported() throws Exception {
        for (int i = 0; i < RunnerDiag.CAPACITY + 5; i++) {
            RunnerDiag.info("chrome", "chrome_state", null, Map.of("i", i));
        }
        List<String> sent = new ArrayList<>();
        new RunnerDiagEmitter(sent::add).flush();

        JsonNode frame = mapper.readTree(sent.get(0));
        assertEquals(5, frame.path("dropped").asLong());
    }

    @Test
    @DisplayName("Aucune URL brute ni secret ne franchit la trame émise (invariant de confidentialité)")
    void no_secret_reaches_the_wire() throws Exception {
        RunnerDiag.warn("teams", "session_state",
                "redirigé vers https://login.microsoftonline.com/t/authorize?token=SECRET",
                Map.of("url", "https://contoso.sharepoint.com/x?token=SECRET"));
        List<String> sent = new ArrayList<>();
        new RunnerDiagEmitter(sent::add).flush();

        String wire = sent.get(0);
        assertFalse(wire.contains("SECRET"), "aucun secret ne doit franchir la trame");
        assertFalse(wire.contains("login.microsoftonline.com"));
        assertFalse(wire.contains("contoso"));
        // La trame reste analysable et porte des classes, pas des adresses.
        JsonNode frame = mapper.readTree(wire);
        assertEquals("runner_diag", frame.path("type").asText());
        // Le champ url (SharePoint) est réduit à sa classe ; le message (login) à la sienne.
        assertTrue(frame.path("events").get(0).path("fields").path("url").asText().contains("sharepoint"));
        assertTrue(frame.path("events").get(0).path("msg").asText().contains("sign_in"));
    }
}
