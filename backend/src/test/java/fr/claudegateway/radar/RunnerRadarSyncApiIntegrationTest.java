package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import fr.claudegateway.radar.sync.RadarSyncLauncher;

/**
 * F-100 / SF-100-02 — <b>le runner rend compte de la synchro</b> : jeton requis, périmètre tiré du jeton,
 * battement, fin, synchro close → le runner s'arrête.
 */
class RunnerRadarSyncApiIntegrationTest extends RadarSyncIntegrationTestBase {

    private static final String HEADER = "X-Runner-Token";

    @Autowired private RadarSyncLauncher launcher;

    private RadarSync running(RadarScope scope) {
        enable(scope, "22:00", "Europe/Paris", null);
        when(liveness.isAlive(any(), any())).thenReturn(true);
        runnerAcceptsSyncs();
        return launcher.start(scope, RadarSyncTrigger.MANUAL, null);
    }

    private ResultActions runnerPost(UUID syncId, String what, String token, String json) throws Exception {
        var request = post("/api/runner/radar/syncs/" + syncId + "/" + what).contextPath("/api")
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            request.header(HEADER, token);
        }
        return mockMvc.perform(request);
    }

    @Test
    @DisplayName("F-107 / SF-107-04 : la première synchro d'un client est hors réserve, la suivante non")
    void firstSyncIsReserveExemptOnce() throws Exception {
        RadarSync first = running(aliceA);
        assertThat(syncs.findById(first.getId()).orElseThrow().isReserveExempt()).isTrue();
        runnerPost(first.getId(), "finish", runnerToken(aliceA), "{\"status\":\"SUCCEEDED\"}")
                .andExpect(status().isOk());

        RadarSync second = launcher.start(aliceA, RadarSyncTrigger.MANUAL, null);
        assertThat(syncs.findById(second.getId()).orElseThrow().isReserveExempt()).isFalse();
    }

    @Test
    @DisplayName("Battement : heartbeatAt et progression enregistrés ; fin : statut, couverture, poste libéré")
    void heartbeatAndFinish() throws Exception {
        RadarSync sync = running(aliceA);
        String token = runnerToken(aliceA);

        runnerPost(sync.getId(), "progress", token, "{\"phase\":\"conversations\",\"done\":3,\"total\":12}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"));
        RadarSync beating = syncs.findById(sync.getId()).orElseThrow();
        assertThat(beating.getProgress()).contains("\"done\":3").contains("conversations");

        runnerPost(sync.getId(), "finish", token, "{\"status\":\"RUNNING\"}").andExpect(status().isBadRequest());
        runnerPost(sync.getId(), "finish", token,
                "{\"status\":\"PARTIAL\",\"coverage\":{\"sources\":{\"teams\":{\"read\":12}}}}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIAL"));

        RadarSync finished = syncs.findById(sync.getId()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(RadarSyncStatus.PARTIAL);
        assertThat(finished.getFinishedAt()).isNotNull();
        assertThat(finished.getCoverage()).contains("\"read\":12");
        assertThat(hostSettings.findByUserIdAndHostId(alice.getId(), aliceA.hostId()).orElseThrow().getRunningSyncId())
                .isNull();

        // Synchro close : le runner s'arrête.
        runnerPost(sync.getId(), "progress", token, "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("PARTIAL"));
        runnerPost(sync.getId(), "finish", token, "{\"status\":\"SUCCEEDED\"}").andExpect(status().isConflict());

        mockMvc.perform(get(url(aliceA, "/syncs")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$[0].status").value("PARTIAL"))
                .andExpect(jsonPath("$[0].progress.phase").value("conversations"))
                .andExpect(jsonPath("$[0].coverage.sources.teams.read").value(12));
    }

    @Test
    @DisplayName("Jeton absent ou inconnu : 401 ; couverture trop grosse : 400")
    void tokenRequired() throws Exception {
        RadarSync sync = running(aliceA);
        runnerPost(sync.getId(), "progress", null, "{}").andExpect(status().isUnauthorized());
        runnerPost(sync.getId(), "finish", "inconnu", "{\"status\":\"SUCCEEDED\"}").andExpect(status().isUnauthorized());
        String huge = "{\"status\":\"SUCCEEDED\",\"coverage\":{\"x\":\"" + "a".repeat(17_000) + "\"}}";
        runnerPost(sync.getId(), "finish", runnerToken(aliceA), huge).andExpect(status().isBadRequest());
        assertThat(syncs.findById(sync.getId()).orElseThrow().getStatus()).isEqualTo(RadarSyncStatus.RUNNING);
    }

    @Test
    @DisplayName("Isolation : le jeton du poste B d'Alice ou celui de Bob ne peut ni battre ni finir la synchro du poste A")
    void isolation() throws Exception {
        RadarSync sync = running(aliceA);

        runnerPost(sync.getId(), "progress", runnerToken(aliceB), "{}").andExpect(status().isNotFound());
        runnerPost(sync.getId(), "finish", runnerToken(aliceB), "{\"status\":\"FAILED\"}").andExpect(status().isNotFound());
        runnerPost(sync.getId(), "finish", runnerToken(bobScope), "{\"status\":\"FAILED\"}").andExpect(status().isNotFound());

        assertThat(syncs.findById(sync.getId()).orElseThrow().getStatus()).isEqualTo(RadarSyncStatus.RUNNING);
        assertThat(hostSettings.findByUserIdAndHostId(alice.getId(), aliceA.hostId()).orElseThrow().getRunningSyncId())
                .isEqualTo(sync.getId());
    }
}
