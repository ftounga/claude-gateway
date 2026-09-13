package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.radar.sync.RadarSyncLauncher;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;

/**
 * F-100 / SF-100-02 — <b>l'heure du soir et « Synchroniser maintenant »</b> par l'API.
 */
class RadarScheduleApiIntegrationTest extends RadarSyncIntegrationTestBase {

    private org.springframework.test.web.servlet.ResultActions putSchedule(RadarScope scope, String token, String json)
            throws Exception {
        return mockMvc.perform(put(url(scope, "/schedule")).contextPath("/api").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private org.springframework.test.web.servlet.ResultActions syncNow(RadarScope scope, String token) throws Exception {
        return mockMvc.perform(post(url(scope, "/syncs")).contextPath("/api").header("Authorization", "Bearer " + token));
    }

    @Test
    @DisplayName("Activer exige la confirmation de l'autorisation du client ; heure et fuseau validés ; vue rendue")
    void activation() throws Exception {
        putSchedule(aliceA, aliceToken, "{\"enabled\":true}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("radar_invalid"));
        putSchedule(aliceA, aliceToken, "{\"enabled\":true,\"clientAuthorizationConfirmed\":true,\"syncTime\":\"25:00\"}")
                .andExpect(status().isBadRequest());
        putSchedule(aliceA, aliceToken, "{\"enabled\":true,\"clientAuthorizationConfirmed\":true,\"timeZone\":\"Mars/Olympus\"}")
                .andExpect(status().isBadRequest());

        putSchedule(aliceA, aliceToken,
                "{\"enabled\":true,\"clientAuthorizationConfirmed\":true,\"syncTime\":\"21:30\",\"timeZone\":\"America/New_York\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.syncTime").value("21:30"))
                .andExpect(jsonPath("$.timeZone").value("America/New_York"))
                .andExpect(jsonPath("$.clientAuthorizedAt").exists())
                .andExpect(jsonPath("$.nextSyncAt").exists())
                .andExpect(jsonPath("$.running").doesNotExist());

        // L'autorisation est donnée une fois : désactiver puis réactiver ne la redemande pas.
        putSchedule(aliceA, aliceToken, "{\"enabled\":false}").andExpect(status().isOk())
                .andExpect(jsonPath("$.nextSyncAt").doesNotExist());
        putSchedule(aliceA, aliceToken, "{\"enabled\":true}").andExpect(status().isOk());

        // Le dernier créneau passé est marqué traité : régler ne déclenche rien.
        assertThat(hostSettings.findByUserIdAndHostId(alice.getId(), aliceA.hostId()).orElseThrow().getLastSlotDate())
                .isNotNull();

        mockMvc.perform(get(url(aliceB, "/schedule")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.syncTime").value("22:00"));
    }

    @Test
    @DisplayName("Synchroniser maintenant : 202, verrou pris, runner appelé avec la fenêtre de 30 jours ; deuxième → 409")
    void syncNowNominal() throws Exception {
        enable(aliceA, "22:00", "Europe/Paris", null);
        when(liveness.isAlive(alice.getId(), aliceA.hostId())).thenReturn(true);
        runnerAcceptsSyncs();

        syncNow(aliceA, aliceToken).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.trigger").value("MANUAL"));

        RadarSync sync = syncs.findAll().get(0);
        assertThat(sync.getStatus()).isEqualTo(RadarSyncStatus.RUNNING);
        assertThat(sync.getTriggerKind()).isEqualTo(RadarSyncTrigger.MANUAL);
        assertThat(hostSettings.findByUserIdAndHostId(alice.getId(), aliceA.hostId()).orElseThrow().getRunningSyncId())
                .isEqualTo(sync.getId());

        ArgumentCaptor<JsonNode> input = ArgumentCaptor.forClass(JsonNode.class);
        ArgumentCaptor<RunnerTarget> target = ArgumentCaptor.forClass(RunnerTarget.class);
        verify(router).call(target.capture(), anyString(), eq(RadarSyncLauncher.COLLECT), input.capture(), anyLong());
        assertThat(target.getValue().hostId()).isEqualTo(aliceA.hostId());
        assertThat(input.getValue().path("sync_id").asText()).isEqualTo(sync.getId().toString());
        assertThat(input.getValue().path("first_sync").asBoolean()).isTrue();
        OffsetDateTime from = OffsetDateTime.parse(input.getValue().path("window_from").asText());
        assertThat(from).isBetween(OffsetDateTime.now().minusDays(30).minusMinutes(5),
                OffsetDateTime.now().minusDays(30).plusMinutes(5));

        syncNow(aliceA, aliceToken).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("radar_sync_running"));
        assertThat(syncs.findAll()).hasSize(1);

        mockMvc.perform(get(url(aliceA, "/schedule")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.running.syncId").value(sync.getId().toString()));
        mockMvc.perform(get(url(aliceA, "/syncs")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$[0].trigger").value("MANUAL"))
                .andExpect(jsonPath("$[0].heartbeatAt").exists());
    }

    @Test
    @DisplayName("Synchro suivante : la fenêtre part de la dernière synchro réussie (recouvrement de 10 min)")
    void incrementalWindow() throws Exception {
        enable(aliceA, "22:00", "Europe/Paris", null);
        when(liveness.isAlive(alice.getId(), aliceA.hostId())).thenReturn(true);
        runnerAcceptsSyncs();
        OffsetDateTime previousStart = OffsetDateTime.now().minusDays(1).withNano(0);
        syncs.save(RadarSync.builder().userId(alice.getId()).hostId(aliceA.hostId()).status(RadarSyncStatus.SUCCEEDED)
                .startedAt(previousStart).finishedAt(previousStart.plusMinutes(20)).build());

        syncNow(aliceA, aliceToken).andExpect(status().isAccepted());

        ArgumentCaptor<JsonNode> input = ArgumentCaptor.forClass(JsonNode.class);
        verify(router).call(any(RunnerTarget.class), anyString(), eq(RadarSyncLauncher.COLLECT), input.capture(), anyLong());
        assertThat(input.getValue().path("first_sync").asBoolean()).isFalse();
        assertThat(input.getValue().path("cursors")).isEmpty();
        assertThat(input.getValue().path("ignored")).isEmpty();
        assertThat(OffsetDateTime.parse(input.getValue().path("window_from").asText()).toInstant())
                .isEqualTo(previousStart.minusMinutes(10).toInstant());
    }

    @Test
    @DisplayName("SF-100-03 : l'entrée porte les curseurs, les fils ignorés et les canaux à lire — du poste seul")
    void collectInputCarriesCursorsAndRules() throws Exception {
        enable(aliceA, "22:00", "Europe/Paris", null);
        when(liveness.isAlive(alice.getId(), aliceA.hostId())).thenReturn(true);
        runnerAcceptsSyncs();
        OffsetDateTime at = OffsetDateTime.parse("2026-09-12T07:05:10Z");
        syncCursors.save(fr.claudegateway.radar.sync.RadarSyncCursor.builder().userId(alice.getId())
                .hostId(aliceA.hostId()).source("TEAMS").conversationRef("19:a@thread.v2").kind("CHANNEL").cursorAt(at).build());
        syncCursors.save(fr.claudegateway.radar.sync.RadarSyncCursor.builder().userId(alice.getId())
                .hostId(aliceB.hostId()).source("TEAMS").conversationRef("19:poste-b@thread.v2").cursorAt(at).build());
        threadRules.save(fr.claudegateway.radar.sync.RadarThreadRule.builder().userId(alice.getId()).hostId(aliceA.hostId())
                .conversationRef("19:bruit@thread.v2").rule(fr.claudegateway.radar.sync.RadarThreadRule.Rule.IGNORE).build());
        threadRules.save(fr.claudegateway.radar.sync.RadarThreadRule.builder().userId(alice.getId()).hostId(aliceA.hostId())
                .conversationRef("19:canal@thread.v2").rule(fr.claudegateway.radar.sync.RadarThreadRule.Rule.READ_CHANNEL).build());
        threadRules.save(fr.claudegateway.radar.sync.RadarThreadRule.builder().userId(bob.getId()).hostId(bobScope.hostId())
                .conversationRef("19:bob@thread.v2").rule(fr.claudegateway.radar.sync.RadarThreadRule.Rule.IGNORE).build());

        syncNow(aliceA, aliceToken).andExpect(status().isAccepted());

        ArgumentCaptor<JsonNode> input = ArgumentCaptor.forClass(JsonNode.class);
        verify(router).call(any(RunnerTarget.class), anyString(), eq(RadarSyncLauncher.COLLECT), input.capture(), anyLong());
        JsonNode sent = input.getValue();
        assertThat(sent.path("cursors")).hasSize(1);
        assertThat(sent.path("cursors").get(0).path("ref").asText()).isEqualTo("19:a@thread.v2");
        assertThat(sent.path("cursors").get(0).path("kind").asText()).isEqualTo("CHANNEL");
        assertThat(sent.path("cursors").get(0).path("at").asText()).isEqualTo("2026-09-12T07:05:10Z");
        assertThat(sent.path("ignored")).hasSize(1);
        assertThat(sent.path("ignored").get(0).asText()).isEqualTo("19:bruit@thread.v2");
        assertThat(sent.path("read_channels").get(0).asText()).isEqualTo("19:canal@thread.v2");
        assertThat(sent.toString()).doesNotContain("poste-b").doesNotContain("19:bob");
    }

    @Test
    @DisplayName("Refus : non activé 409 ; hors ligne 409 sans synchro ; runner qui refuse → FAILED nommé et poste libéré")
    void syncNowRefusals() throws Exception {
        when(liveness.isAlive(alice.getId(), aliceA.hostId())).thenReturn(true);
        syncNow(aliceA, aliceToken).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("radar_state_conflict"));

        enable(aliceA, "22:00", "Europe/Paris", null);
        when(liveness.isAlive(alice.getId(), aliceA.hostId())).thenReturn(false);
        syncNow(aliceA, aliceToken).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("radar_runner_unavailable"));
        assertThat(syncs.findAll()).isEmpty();

        when(liveness.isAlive(alice.getId(), aliceA.hostId())).thenReturn(true);
        when(router.call(any(RunnerTarget.class), anyString(), eq(RadarSyncLauncher.COLLECT), any(), anyLong()))
                .thenReturn(ok("{\"accepted\":false,\"reason\":\"NO_UPLINK\"}"));
        syncNow(aliceA, aliceToken).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("radar_runner_unavailable"));
        RadarSync refused = syncs.findAll().get(0);
        assertThat(refused.getStatus()).isEqualTo(RadarSyncStatus.FAILED);
        assertThat(refused.getCoverage()).contains("NO_UPLINK");
        assertThat(hostSettings.findByUserIdAndHostId(alice.getId(), aliceA.hostId()).orElseThrow().getRunningSyncId())
                .isNull();

        when(router.call(any(RunnerTarget.class), anyString(), eq(RadarSyncLauncher.COLLECT), any(), anyLong()))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.UNSUPPORTED_TOOL));
        syncNow(aliceA, aliceToken).andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("radar_teams_disabled"));
        assertThat(syncs.findAll()).hasSize(2).allMatch(s -> s.getStatus() == RadarSyncStatus.FAILED);
        assertThat(hostSettings.findByUserIdAndHostId(alice.getId(), aliceA.hostId()).orElseThrow().getRunningSyncId())
                .isNull();
    }

    @Test
    @DisplayName("Isolation : Bob ne règle, ne lance ni ne lit la planification du poste d'Alice ; le poste B n'hérite pas du A")
    void isolation() throws Exception {
        enable(aliceA, "22:00", "Europe/Paris", null);
        when(liveness.isAlive(any(UUID.class), any(UUID.class))).thenReturn(true);
        runnerAcceptsSyncs();

        putSchedule(aliceA, bobToken, "{\"enabled\":false}").andExpect(status().isNotFound());
        mockMvc.perform(get(url(aliceA, "/schedule")).contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        syncNow(aliceA, bobToken).andExpect(status().isNotFound());
        verify(router, never()).call(any(RunnerTarget.class), anyString(), anyString(), any(), anyLong());
        assertThat(hostSettings.findByUserIdAndHostId(alice.getId(), aliceA.hostId()).orElseThrow().isEnabled()).isTrue();

        syncNow(aliceB, aliceToken).andExpect(status().isConflict()); // le poste B n'est pas activé
        assertThat(List.copyOf(syncs.findAll())).isEmpty();
    }
}
