package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.ToolContext;
import fr.claudegateway.runner.ToolOutcome;

/**
 * F-100 / SF-100-02 — <b>le travail de la synchro sur la machine</b> : un seul à la fois, en tâche de
 * fond, qui bat, rend compte, et s'arrête quand la gateway dit que c'est fini.
 */
class RadarSyncAgentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SYNC = "7f000001-0000-4000-8000-000000000001";

    /** Une remontée de papier : note tout, répond ce qu'on lui dit. */
    static final class PaperUplink implements RadarUplink {
        final List<String> calls = new ArrayList<>();
        final List<ObjectNode> finishes = new ArrayList<>();
        final List<ObjectNode> batches = new ArrayList<>();
        Answer progressAnswer = Answer.RUNNING;
        boolean available = true;
        int failFinish;

        @Override
        public Answer progress(String syncId, ObjectNode body) {
            calls.add("progress:" + syncId + ":" + body.path("phase").asText());
            return progressAnswer;
        }

        @Override
        public Answer finish(String syncId, ObjectNode body) throws IOException {
            calls.add("finish:" + syncId);
            if (failFinish-- > 0) {
                throw new IOException("réseau coupé");
            }
            finishes.add(body);
            return Answer.RUNNING;
        }

        @Override
        public JsonNode batch(String syncId, ObjectNode body) {
            calls.add("batch:" + syncId);
            batches.add(body);
            ObjectNode answer = MAPPER.createObjectNode();
            answer.put("status", "PENDING");
            return answer;
        }

        @Override
        public boolean available() {
            return available;
        }
    }

    private static ObjectNode input(String syncId) {
        ObjectNode input = MAPPER.createObjectNode();
        input.put("sync_id", syncId);
        input.put("trigger", "SCHEDULED");
        input.put("first_sync", true);
        input.put("window_from", "2026-08-14T20:00:00Z");
        return input;
    }

    @Test
    @DisplayName("Sans collecteur : battement, puis fin FAILED nommée — jamais une synchro vide réussie")
    void withoutCollectorFailsLoudly() {
        PaperUplink uplink = new PaperUplink();
        RadarSyncAgent agent = new RadarSyncAgent(uplink, RadarCollector::unavailable, Runnable::run, null, null);

        RadarSyncAgent.Acceptance acceptance = agent.accept(RadarAssignment.from(input(SYNC)));

        assertTrue(acceptance.accepted());
        assertEquals(List.of("progress:" + SYNC + ":start", "finish:" + SYNC), uplink.calls);
        ObjectNode finish = uplink.finishes.get(0);
        assertEquals("FAILED", finish.path("status").asText());
        assertEquals("COLLECTOR_UNAVAILABLE", finish.path("coverage").path("failure").path("code").asText());
        assertEquals(null, agent.runningSyncId(), "le poste est libéré à la fin");
    }

    @Test
    @DisplayName("Un seul travail à la fois : une seconde synchro reçoit BUSY avec la synchro en cours")
    void oneJobAtATime() {
        Deque<Runnable> pending = new ArrayDeque<>();
        PaperUplink uplink = new PaperUplink();
        RadarSyncAgent agent = new RadarSyncAgent(uplink, RadarCollector::unavailable, pending::add, null, null);

        assertTrue(agent.accept(RadarAssignment.from(input(SYNC))).accepted());
        RadarSyncAgent.Acceptance second = agent.accept(
                RadarAssignment.from(input("7f000001-0000-4000-8000-000000000002")));

        assertFalse(second.accepted());
        assertEquals("BUSY", second.reason());
        assertEquals(SYNC, second.runningSyncId());
        pending.poll().run();
        assertTrue(agent.accept(RadarAssignment.from(input("7f000001-0000-4000-8000-000000000003"))).accepted());
    }

    @Test
    @DisplayName("La gateway dit que la synchro est close : la collecte s'arrête et aucune fin n'est envoyée")
    void stopsWhenGatewaySaysSo() {
        PaperUplink uplink = new PaperUplink();
        List<Boolean> seen = new ArrayList<>();
        RadarCollector collector = (assignment, context) -> {
            uplink.progressAnswer = RadarUplink.Answer.STOPPED;
            seen.add(context.progress("conversations", 1, 10));
            seen.add(context.stopped());
            return new RadarCollector.Outcome("SUCCEEDED", MAPPER.createObjectNode());
        };
        RadarSyncAgent agent = new RadarSyncAgent(uplink, () -> collector, Runnable::run, null, null);

        agent.accept(RadarAssignment.from(input(SYNC)));

        assertEquals(List.of(false, true), seen);
        assertTrue(uplink.finishes.isEmpty());
    }

    @Test
    @DisplayName("Collecte qui lève : FAILED COLLECTOR_ERROR ; fin perdue deux fois : réessayée")
    void collectorErrorAndFinishRetry() {
        PaperUplink uplink = new PaperUplink();
        uplink.failFinish = 2;
        RadarCollector broken = (assignment, context) -> {
            throw new IllegalStateException("bogue");
        };
        new RadarSyncAgent(uplink, () -> broken, Runnable::run, null, null).accept(RadarAssignment.from(input(SYNC)));

        assertEquals(1, uplink.finishes.size());
        assertEquals("COLLECTOR_ERROR", uplink.finishes.get(0).path("coverage").path("failure").path("code").asText());
        assertEquals(3, uplink.calls.stream().filter(call -> call.startsWith("finish")).count());
    }

    @Test
    @DisplayName("teams_radar_collect : sans remontée NO_UPLINK, volet coupé TEAMS_DISABLED, entrée illisible refusée")
    void toolRefusals() throws Exception {
        PaperTeams teams = new PaperTeams();
        ToolOutcome noUplink = teams.tools().execute(RadarTools.COLLECT, input(SYNC), ToolContext.none());
        assertEquals("NO_UPLINK", MAPPER.readTree(noUplink.content()).path("reason").asText());

        PaperUplink offline = new PaperUplink();
        offline.available = false;
        ToolOutcome noToken = teams.tools().withRadarUplink(offline, line -> { })
                .execute(RadarTools.COLLECT, input(SYNC), ToolContext.none());
        assertEquals("NO_UPLINK", MAPPER.readTree(noToken.content()).path("reason").asText());

        ToolOutcome disabled = TeamsTools.disabled("coupé").execute(RadarTools.COLLECT, input(SYNC), ToolContext.none());
        assertEquals("TEAMS_DISABLED", MAPPER.readTree(disabled.content()).path("reason").asText());

        ObjectNode bad = input("../../admin");
        ToolOutcome refused = teams.tools().withRadarAgent(
                new RadarSyncAgent(new PaperUplink(), RadarCollector::unavailable, Runnable::run, null, null))
                .execute(RadarTools.COLLECT, bad, ToolContext.none());
        assertFalse(refused.ok());

        PaperUplink uplink = new PaperUplink();
        ToolOutcome accepted = teams.tools().withRadarAgent(
                new RadarSyncAgent(uplink, RadarCollector::unavailable, Runnable::run, null, null))
                .execute(RadarTools.COLLECT, input(SYNC), ToolContext.none());
        assertTrue(MAPPER.readTree(accepted.content()).path("accepted").asBoolean());
        assertFalse(TeamsTools.CATALOG.contains(RadarTools.COLLECT));
    }
}
