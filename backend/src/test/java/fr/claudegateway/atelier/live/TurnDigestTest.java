package fr.claudegateway.atelier.live;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/** Le résumé d'un tour, nourri par ses propres événements (F-191 / SF-191-01). */
class TurnDigestTest {

    private final LiveTurn turn = new LiveTurn(UUID.randomUUID(), UUID.randomUUID(), new ObjectMapper());

    record Step(String type, String path) {
    }

    record PlanStep(String title, String status) {
    }

    record Plan(List<PlanStep> steps) {
    }

    @Test
    @DisplayName("un tour neuf est en cours, sans plan ni outil, avec sa demande tronquée")
    void freshTurn() {
        turn.digest().demand("  " + "x".repeat(300) + "  ");

        TurnSummary s = turn.summary();

        assertThat(s.state()).isEqualTo("running");
        assertThat(s.demand()).hasSize(TurnDigest.MAX_DEMAND_CHARS).endsWith("…");
        assertThat(s.planTotal()).isZero();
        assertThat(s.tool()).isNull();
        assertThat(s.endedAt()).isNull();
    }

    @Test
    @DisplayName("le plan donne étapes faites / prévues et l'étape active ; l'action donne l'outil courant")
    void planAndTool() {
        turn.publish("plan", new Plan(List.of(new PlanStep("Lire", "done"), new PlanStep("Corriger", "active"),
                new PlanStep("Tester", "pending"))));
        turn.publish("action", new Step("bash", "mvn test"));

        TurnSummary s = turn.summary();

        assertThat(s.planDone()).isEqualTo(1);
        assertThat(s.planTotal()).isEqualTo(3);
        assertThat(s.planStep()).isEqualTo("Corriger");
        assertThat(s.tool()).isEqualTo("bash");
    }

    @Test
    @DisplayName("l'exécution Managed Agent publie {tool} : l'outil est lu aussi")
    void agentActionShape() {
        turn.publish("action", Map.of("tool", "read", "toolUseId", "t1", "detail", "pom.xml"));

        assertThat(turn.summary().tool()).isEqualTo("read");
    }

    @Test
    @DisplayName("une demande d'autorisation met le tour en attente ; sa résolution le remet en cours")
    void waitingForApproval() {
        turn.publish(LiveTurn.CONFIRM_REQUEST, Map.of("toolUseId", "a"));
        assertThat(turn.summary().state()).isEqualTo("waiting");
        assertThat(turn.summary().waiting()).isEqualTo("approval");

        turn.publish(LiveTurn.CONFIRM_RESOLVED, Map.of("toolUseId", "a", "decision", "allow"));
        assertThat(turn.summary().state()).isEqualTo("running");
        assertThat(turn.summary().waiting()).isNull();
    }

    @Test
    @DisplayName("une question met le tour en attente « question »")
    void waitingForQuestion() {
        turn.publish(LiveTurn.QUESTION_REQUEST, Map.of("callId", "q"));

        assertThat(turn.summary().waiting()).isEqualTo("question");
    }

    @Test
    @DisplayName("les délégations sont des sous-agents, finis dès qu'une action non déléguée suit")
    void subagents() {
        turn.publish("action", new Step("task", "Relire   le module\nde facturation"));
        turn.publish("action", new Step("explore", "où est la config ?"));

        List<TurnDigest.SubAgent> running = turn.summary().subagents();
        assertThat(running).extracting(TurnDigest.SubAgent::state).containsExactly("running", "running");
        assertThat(running.get(0).label()).isEqualTo("Relire le module de facturation");
        assertThat(running.get(0).kind()).isEqualTo("task");

        turn.publish("action", new Step("read", "a.txt"));
        assertThat(turn.summary().subagents()).extracting(TurnDigest.SubAgent::state)
                .containsExactly("done", "done");
    }

    @Test
    @DisplayName("au plus six sous-agents retenus, les plus récents")
    void subagentsBounded() {
        for (int i = 0; i < 9; i++) {
            turn.publish("action", new Step("task", "t" + i));
        }

        assertThat(turn.summary().subagents()).hasSize(TurnDigest.MAX_SUBAGENTS)
                .first().extracting(TurnDigest.SubAgent::label).isEqualTo("t3");
    }

    @Test
    @DisplayName("fini : l'issue est done, failed sur erreur, interrupted si l'événement le dit")
    void outcomes() {
        turn.publish("done", Map.of("reply", "ok"));
        turn.finish();
        TurnSummary done = turn.summary();
        assertThat(done.state()).isEqualTo("done");
        assertThat(done.endedAt()).isNotNull();
        assertThat(done.tool()).isNull();

        LiveTurn failing = new LiveTurn(UUID.randomUUID(), UUID.randomUUID(), new ObjectMapper());
        failing.publish("action", new Step("task", "x"));
        failing.publish("error", Map.of("error", "provider_error"));
        failing.finish();
        assertThat(failing.summary().state()).isEqualTo("failed");
        assertThat(failing.summary().subagents()).extracting(TurnDigest.SubAgent::state).containsExactly("done");

        LiveTurn stopped = new LiveTurn(UUID.randomUUID(), UUID.randomUUID(), new ObjectMapper());
        stopped.publish("done", Map.of("interrupted", true));
        stopped.finish();
        assertThat(stopped.summary().state()).isEqualTo("interrupted");
    }

    @Test
    @DisplayName("une charge utile inattendue ne casse ni le résumé ni le tour")
    void toleratesOddPayloads() {
        turn.publish("plan", Map.of("steps", "pas une liste"));
        turn.publish("action", Map.of("autre", 1));

        assertThat(turn.summary().state()).isEqualTo("running");
        assertThat(turn.cursor()).isEqualTo(2);
    }
}
