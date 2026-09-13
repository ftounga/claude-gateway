package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.radar.sync.RadarSyncControlService;
import fr.claudegateway.radar.sync.RadarSyncLauncher;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;

/**
 * F-100 / SF-100-04 — <b>la couverture et la progression</b> par l'API : la phrase de tête, la progression,
 * l'annulation, et les gestes <i>ignorer ce fil</i> / <i>lire ce canal</i>.
 */
class RadarCoverageApiIntegrationTest extends RadarSyncIntegrationTestBase {

    @Autowired private RadarSyncLauncher launcher;

    private RadarSync running(RadarScope scope) {
        enable(scope, "22:00", "Europe/Paris", null);
        when(liveness.isAlive(any(), any())).thenReturn(true);
        runnerAcceptsSyncs();
        return launcher.start(scope, RadarSyncTrigger.MANUAL, null);
    }

    private ResultActions as(String token, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request)
            throws Exception {
        return mockMvc.perform(request.contextPath("/api").header("Authorization", "Bearer " + token));
    }

    private ResultActions runner(UUID syncId, String what, String token, String json) throws Exception {
        return mockMvc.perform(post("/api/runner/radar/syncs/" + syncId + "/" + what).contextPath("/api")
                .header("X-Runner-Token", token).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    @DisplayName("Progression dans « running » et en tête ; fin partielle : la couverture le dit, avec ses gestes")
    void progressAndSummary() throws Exception {
        RadarSync sync = running(aliceA);
        String token = runnerToken(aliceA);
        runner(sync.getId(), "progress", token, "{\"phase\":\"conversations\",\"done\":12,\"total\":40}")
                .andExpect(status().isOk());

        as(aliceToken, get(url(aliceA, "/schedule")))
                .andExpect(jsonPath("$.running.phase").value("conversations"))
                .andExpect(jsonPath("$.running.done").value(12))
                .andExpect(jsonPath("$.running.total").value(40));
        as(aliceToken, get(url(aliceA, "/syncs")))
                .andExpect(jsonPath("$[0].summary.headline").value("Synchro en cours : 12 conversations sur 40."));

        as(aliceToken, post(url(aliceA, "/thread-rules")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationRef\":\"19:bruit@thread.v2\",\"rule\":\"IGNORE\",\"label\":\"Afterwork\"}"))
                .andExpect(status().isOk());
        runner(sync.getId(), "finish", token, "{\"status\":\"PARTIAL\",\"coverage\":{\"conversations\":{\"read\":5,"
                + "\"partial\":1},\"channels\":{\"unreadActive\":2},\"threads\":[{\"ref\":\"19:bruit@thread.v2\","
                + "\"label\":\"Afterwork\",\"kind\":\"CONVERSATION\",\"status\":\"PARTIAL\"},{\"ref\":\"19:canal@thread.v2\","
                + "\"label\":\"Migration\",\"kind\":\"CHANNEL\",\"status\":\"UNREAD_CHANNEL\"}]}}")
                .andExpect(status().isOk());

        as(aliceToken, get(url(aliceA, "/syncs")))
                .andExpect(jsonPath("$[0].summary.headline")
                        .value("Synchro partielle : 1 fil non entièrement lu, 2 canaux actifs non lus."))
                .andExpect(jsonPath("$[0].summary.items[0].rule").value("IGNORE"))
                .andExpect(jsonPath("$[0].summary.items[1].actions[0]").value("READ_CHANNEL"));
    }

    @Test
    @DisplayName("Annuler : CANCELLED, poste libéré, runner prévenu ; le runner reçoit 409 ensuite ; déjà close : 409")
    void cancel() throws Exception {
        RadarSync sync = running(aliceA);
        String token = runnerToken(aliceA);
        when(router.call(any(RunnerTarget.class), anyString(), eq(RadarSyncControlService.CANCEL), any(), anyLong()))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE));

        as(aliceToken, post(url(aliceA, "/syncs/" + sync.getId() + "/cancel"))).andExpect(status().isOk());

        RadarSync cancelled = syncs.findById(sync.getId()).orElseThrow();
        assertThat(cancelled.getStatus()).isEqualTo(RadarSyncStatus.CANCELLED);
        assertThat(cancelled.getCoverage()).contains("\"cancelled\":true");
        assertThat(hostSettings.findByUserIdAndHostId(alice.getId(), aliceA.hostId()).orElseThrow().getRunningSyncId())
                .isNull();
        ArgumentCaptor<JsonNode> input = ArgumentCaptor.forClass(JsonNode.class);
        verify(router).call(any(RunnerTarget.class), anyString(), eq(RadarSyncControlService.CANCEL), input.capture(),
                anyLong());
        assertThat(input.getValue().path("sync_id").asText()).isEqualTo(sync.getId().toString());

        runner(sync.getId(), "progress", token, "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
        as(aliceToken, post(url(aliceA, "/syncs/" + sync.getId() + "/cancel"))).andExpect(status().isConflict());
        as(aliceToken, get(url(aliceA, "/syncs")))
                .andExpect(jsonPath("$[0].summary.headline").value(org.hamcrest.Matchers.startsWith("Synchro annulée à ")));
    }

    @Test
    @DisplayName("Règles : pose idempotente, validation, liste, annulation — puis lues par la collecte suivante")
    void threadRules() throws Exception {
        String ignore = "{\"conversationRef\":\"19:bruit@thread.v2\",\"rule\":\"IGNORE\",\"label\":\"Afterwork\"}";
        String first = as(aliceToken, post(url(aliceA, "/thread-rules")).contentType(MediaType.APPLICATION_JSON).content(ignore))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String second = as(aliceToken, post(url(aliceA, "/thread-rules")).contentType(MediaType.APPLICATION_JSON).content(ignore))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(new com.fasterxml.jackson.databind.ObjectMapper().readTree(second).path("id"))
                .isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper().readTree(first).path("id"));
        assertThat(threadRules.findAll()).hasSize(1);

        as(aliceToken, post(url(aliceA, "/thread-rules")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationRef\":\"\",\"rule\":\"IGNORE\"}")).andExpect(status().isBadRequest());
        as(aliceToken, post(url(aliceA, "/thread-rules")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationRef\":\"19:x\",\"rule\":\"TOUT_LIRE\"}")).andExpect(status().isBadRequest());
        as(aliceToken, post(url(aliceA, "/thread-rules")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationRef\":\"19:x\",\"rule\":\"IGNORE\",\"label\":\"" + "a".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest());
        as(aliceToken, post(url(aliceA, "/thread-rules")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationRef\":\"19:canal@thread.v2\",\"rule\":\"READ_CHANNEL\"}")).andExpect(status().isOk());

        as(aliceToken, get(url(aliceA, "/thread-rules"))).andExpect(jsonPath("$.length()").value(2));

        running(aliceA);
        ArgumentCaptor<JsonNode> input = ArgumentCaptor.forClass(JsonNode.class);
        verify(router).call(any(RunnerTarget.class), anyString(), eq(RadarSyncLauncher.COLLECT), input.capture(), anyLong());
        assertThat(input.getValue().path("ignored").get(0).asText()).isEqualTo("19:bruit@thread.v2");
        assertThat(input.getValue().path("read_channels").get(0).asText()).isEqualTo("19:canal@thread.v2");

        UUID ruleId = UUID.fromString(new com.fasterxml.jackson.databind.ObjectMapper().readTree(first).path("id").asText());
        as(aliceToken, delete(url(aliceA, "/thread-rules/" + ruleId))).andExpect(status().isNoContent());
        as(aliceToken, delete(url(aliceA, "/thread-rules/" + ruleId))).andExpect(status().isNotFound());
        assertThat(threadRules.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("Isolation : Bob n'annule, ne lit ni ne règle rien du poste d'Alice ; la règle du poste A n'est pas sur le B")
    void isolation() throws Exception {
        RadarSync sync = running(aliceA);
        as(aliceToken, post(url(aliceA, "/thread-rules")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationRef\":\"19:a@thread.v2\",\"rule\":\"IGNORE\"}")).andExpect(status().isOk());
        UUID ruleId = threadRules.findAll().get(0).getId();

        as(bobToken, post(url(aliceA, "/syncs/" + sync.getId() + "/cancel"))).andExpect(status().isNotFound());
        as(bobToken, get(url(aliceA, "/thread-rules"))).andExpect(status().isNotFound());
        as(bobToken, post(url(aliceA, "/thread-rules")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"conversationRef\":\"19:b\",\"rule\":\"IGNORE\"}")).andExpect(status().isNotFound());
        as(bobToken, delete(url(aliceA, "/thread-rules/" + ruleId))).andExpect(status().isNotFound());
        as(aliceToken, post(url(aliceB, "/syncs/" + sync.getId() + "/cancel"))).andExpect(status().isNotFound());
        as(aliceToken, delete(url(aliceB, "/thread-rules/" + ruleId))).andExpect(status().isNotFound());
        as(aliceToken, get(url(aliceB, "/thread-rules"))).andExpect(jsonPath("$.length()").value(0));

        assertThat(syncs.findById(sync.getId()).orElseThrow().getStatus()).isEqualTo(RadarSyncStatus.RUNNING);
        assertThat(threadRules.findAll()).hasSize(1);
    }
}
