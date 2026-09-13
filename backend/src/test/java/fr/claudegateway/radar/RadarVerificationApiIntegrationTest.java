package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fr.claudegateway.radar.sync.RadarRunnerCalls;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;

/**
 * F-100 / SF-100-01 — <b>la vérification guidée</b> par l'API : le runner du poste est interrogé,
 * les cases vues restent vues, rien n'est écrit quand le poste ne répond pas, et un poste d'autrui
 * n'est jamais atteint.
 */
class RadarVerificationApiIntegrationTest extends RadarSyncIntegrationTestBase {

    private static RunnerCallResult ok(String json) {
        return new RunnerCallResult(true, json, false, null, 12L, null, null, null, "", false);
    }

    private static String runner(boolean session, boolean conversations, boolean meetings, String transcriptReason) {
        boolean transcripts = "SEEN".equals(transcriptReason);
        return "{\"tool\":\"teams_radar_verify\",\"checks\":{"
                + "\"session\":{\"ok\":" + session + ",\"state\":\"" + (session ? "LINKED" : "NOT_SIGNED_IN")
                + "\",\"sentence\":\"" + (session ? "Session active." : "Reconnectez-vous à Teams.") + "\"},"
                + "\"conversations\":{\"ok\":" + conversations + ",\"count\":" + (conversations ? 4 : 0)
                + ",\"sentence\":\"c\"},"
                + "\"meetings\":{\"ok\":" + meetings + ",\"count\":" + (meetings ? 1 : 0) + ",\"sentence\":\"m\"},"
                + "\"transcripts\":{\"ok\":" + transcripts + ",\"count\":" + (transcripts ? 30 : 0)
                + ",\"reason\":\"" + transcriptReason + "\",\"sentence\":\"t\"}}}";
    }

    private void runnerAnswers(RunnerCallResult result) {
        when(router.call(any(RunnerTarget.class), anyString(), eq(RadarRunnerCalls.VERIFY), any(), anyLong()))
                .thenReturn(result);
    }

    @Test
    @DisplayName("Nominal : le poste du chemin est interrogé, sans projet, et le résultat est enregistré et journalisé")
    void verifiesTheHostOfThePath() throws Exception {
        runnerAnswers(ok(runner(true, true, false, "NOT_SEEN")));

        mockMvc.perform(post(url(aliceA, "/verification")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.session.ok").value(true))
                .andExpect(jsonPath("$.conversations.ok").value(true))
                .andExpect(jsonPath("$.conversations.count").value(4))
                .andExpect(jsonPath("$.meetings.ok").value(false))
                .andExpect(jsonPath("$.transcripts.state").value("NOT_SEEN"));

        ArgumentCaptor<RunnerTarget> target = ArgumentCaptor.forClass(RunnerTarget.class);
        verify(router).call(target.capture(), anyString(), eq(RadarRunnerCalls.VERIFY), any(), anyLong());
        assertThat(target.getValue().hostId()).isEqualTo(aliceA.hostId());
        assertThat(target.getValue().workspaceId()).isNull();
        assertThat(hostSettings.findByUserIdAndHostId(alice.getId(), aliceA.hostId())).get()
                .satisfies(row -> assertThat(row.getVerifiedAt()).isNotNull());
        assertThat(runnerAudits.findAll()).anySatisfy(row -> assertThat(row.getTool()).isEqualTo(RadarRunnerCalls.VERIFY));
    }

    @Test
    @DisplayName("Le fil, puis la réunion et sa transcription : ce qui a été vu reste vu, la session dit le dernier appel")
    void checksAreStickyAcrossCalls() throws Exception {
        runnerAnswers(ok(runner(true, true, false, "NOT_SEEN")));
        mockMvc.perform(post(url(aliceA, "/verification")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());

        runnerAnswers(ok(runner(true, false, true, "SEEN")));
        mockMvc.perform(post(url(aliceA, "/verification")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversations.ok").value(true))
                .andExpect(jsonPath("$.meetings.ok").value(true))
                .andExpect(jsonPath("$.transcripts.ok").value(true))
                .andExpect(jsonPath("$.complete").value(true));

        runnerAnswers(ok(runner(false, false, false, "NOT_SEEN")));
        mockMvc.perform(post(url(aliceA, "/verification")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.session.ok").value(false))
                .andExpect(jsonPath("$.session.state").value("NOT_SIGNED_IN"))
                .andExpect(jsonPath("$.session.sentence").value("Reconnectez-vous à Teams."))
                .andExpect(jsonPath("$.conversations.ok").value(true))
                .andExpect(jsonPath("$.complete").value(false));

        mockMvc.perform(get(url(aliceA, "/verification")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transcripts.ok").value(true));

        mockMvc.perform(delete(url(aliceA, "/verification")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversations.ok").value(false));
        mockMvc.perform(get(url(aliceA, "/verification")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.verifiedAt").doesNotExist())
                .andExpect(jsonPath("$.transcripts.ok").value(false));
    }

    @Test
    @DisplayName("Poste hors ligne, volet Teams absent, délai, réponse illisible : 409 nommé, rien d'enregistré")
    void failuresWriteNothing() throws Exception {
        runnerAnswers(RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_UNAVAILABLE));
        mockMvc.perform(post(url(aliceA, "/verification")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("radar_runner_unavailable"));

        runnerAnswers(RunnerCallResult.backendError(RunnerErrorCodes.UNSUPPORTED_TOOL));
        mockMvc.perform(post(url(aliceA, "/verification")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("radar_teams_disabled"));

        runnerAnswers(RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_TIMEOUT));
        mockMvc.perform(post(url(aliceA, "/verification")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("radar_runner_unavailable"));

        runnerAnswers(ok("{\"tool\":\"teams_radar_verify\"}"));
        mockMvc.perform(post(url(aliceA, "/verification")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("radar_runner_unavailable"));

        assertThat(hostSettings.findByUserIdAndHostId(alice.getId(), aliceA.hostId())).isEmpty();
        mockMvc.perform(get(url(aliceA, "/verification")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.complete").value(false))
                .andExpect(jsonPath("$.session.ok").value(false));
    }

    @Test
    @DisplayName("Isolation : Bob n'atteint jamais le poste d'Alice ; le poste B d'Alice n'hérite pas du A ; purge du A seul")
    void isolation() throws Exception {
        runnerAnswers(ok(runner(true, true, true, "SEEN")));

        mockMvc.perform(post(url(aliceA, "/verification")).contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(url(aliceA, "/verification")).contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete(url(aliceA, "/verification")).contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        verify(router, never()).call(any(RunnerTarget.class), anyString(), anyString(), any(), anyLong());

        mockMvc.perform(post(url(aliceA, "/verification")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
        mockMvc.perform(post(url(aliceB, "/verification")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
        runnerAnswers(ok(runner(true, false, false, "NOT_SEEN")));
        mockMvc.perform(delete(url(aliceB, "/verification")).contextPath("/api").header("Authorization", "Bearer " + aliceToken));
        mockMvc.perform(post(url(aliceB, "/verification")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.meetings.ok").value(false));
        mockMvc.perform(get(url(aliceA, "/verification")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.meetings.ok").value(true));

        purgeService.purge(aliceA, RadarPurgeReason.USER_REQUEST);
        assertThat(hostSettings.findByUserIdAndHostId(alice.getId(), aliceA.hostId())).isEmpty();
        assertThat(hostSettings.findByUserIdAndHostId(alice.getId(), aliceB.hostId())).isPresent();
    }

    @org.springframework.beans.factory.annotation.Autowired private RadarPurgeService purgeService;
}
