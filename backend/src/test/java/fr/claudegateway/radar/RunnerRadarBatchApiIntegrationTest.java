package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import fr.claudegateway.radar.analysis.RadarAnalysisBatchStatus;
import fr.claudegateway.radar.sync.RadarSyncCursor;
import fr.claudegateway.radar.sync.RadarSyncLauncher;

/**
 * F-100 / SF-100-03 — <b>les lots de la collecte</b> : déposés dans la file d'analyse (idempotents), puis les
 * curseurs avancent — jamais avant, jamais en arrière — et rien ne franchit la frontière d'un poste.
 */
class RunnerRadarBatchApiIntegrationTest extends RadarSyncIntegrationTestBase {

    private static final String HEADER = "X-Runner-Token";

    @Autowired private RadarSyncLauncher launcher;

    private RadarSync running(RadarScope scope) {
        enable(scope, "22:00", "Europe/Paris", null);
        when(liveness.isAlive(any(), any())).thenReturn(true);
        runnerAcceptsSyncs();
        return launcher.start(scope, RadarSyncTrigger.MANUAL, null);
    }

    private static String body(String batchKey, String cursorAt, String text) {
        return "{\"batch\":{\"batchKey\":\"" + batchKey + "\",\"exchanges\":[{\"source\":\"TEAMS_MESSAGE\","
                + "\"conversationRef\":\"19:abc@thread.v2\",\"title\":\"Chantier MFA\",\"messages\":[{"
                + "\"sourceRef\":\"19:abc@thread.v2/1\",\"occurredAt\":\"2026-09-12T07:05:10Z\","
                + "\"authorKey\":\"marc@client.fr\",\"authorName\":\"Marc\",\"fromMe\":false,\"text\":\"" + text + "\"}]}]},"
                + "\"cursors\":[{\"ref\":\"19:abc@thread.v2\",\"kind\":\"CONVERSATION\",\"at\":\"" + cursorAt + "\"}]}";
    }

    private ResultActions postBatch(UUID syncId, String token, String json) throws Exception {
        var request = post("/api/runner/radar/syncs/" + syncId + "/batches").contextPath("/api")
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (token != null) {
            request.header(HEADER, token);
        }
        return mockMvc.perform(request);
    }

    private OffsetDateTime cursorOf(RadarScope scope) {
        return syncCursors.findByUserIdAndHostIdAndSourceAndConversationRef(scope.userId(), scope.hostId(),
                RadarSyncCursor.SOURCE_TEAMS, "19:abc@thread.v2").map(RadarSyncCursor::getCursorAt).orElse(null);
    }

    @Test
    @DisplayName("Lot déposé dans la file (PENDING), curseur avancé ; rejoué : doublon, rien de réécrit ; curseur jamais reculé")
    void depositAndAdvance() throws Exception {
        RadarSync sync = running(aliceA);
        String token = runnerToken(aliceA);

        postBatch(sync.getId(), token, body("teams:k1", "2026-09-12T07:05:10Z", "Tu peux me mettre en relation ?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RUNNING"))
                .andExpect(jsonPath("$.batchStatus").value("PENDING"))
                .andExpect(jsonPath("$.duplicate").value(false))
                .andExpect(jsonPath("$.cursors").value(1));

        assertThat(analysisBatches.findAll()).singleElement().satisfies(b -> {
            assertThat(b.getSyncId()).isEqualTo(sync.getId());
            assertThat(b.getHostId()).isEqualTo(aliceA.hostId());
            assertThat(b.getStatus()).isEqualTo(RadarAnalysisBatchStatus.PENDING);
        });
        assertThat(cursorOf(aliceA).toInstant().toString()).isEqualTo("2026-09-12T07:05:10Z");

        postBatch(sync.getId(), token, body("teams:k1", "2026-09-12T07:05:10Z", "Tu peux me mettre en relation ?"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicate").value(true));
        assertThat(analysisBatches.findAll()).hasSize(1);

        // Un curseur plus ancien ne recule pas celui du fil ; sans lot, les curseurs peuvent avancer seuls.
        postBatch(sync.getId(), token, "{\"cursors\":[{\"ref\":\"19:abc@thread.v2\",\"at\":\"2026-09-01T00:00:00Z\"}]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.cursors").value(0));
        assertThat(cursorOf(aliceA).toInstant().toString()).isEqualTo("2026-09-12T07:05:10Z");
        assertThat(syncs.findById(sync.getId()).orElseThrow().getHeartbeatAt()).isNotNull();
    }

    @Test
    @DisplayName("SF-100-05 : un enregistrement déposé a son curseur sous DEPOT, et part dans depot_done — du poste seul")
    void depositRecordingCursor() throws Exception {
        RadarSync sync = running(aliceA);
        String token = runnerToken(aliceA);
        String recording = "{\"batch\":{\"batchKey\":\"depot:k1\",\"exchanges\":[{\"source\":\"LOCAL_RECORDING\","
                + "\"conversationRef\":\"depot:abc\",\"title\":\"Comité budget\",\"messages\":[{"
                + "\"sourceRef\":\"depot:abc/1\",\"occurredAt\":\"2026-09-12T14:30:05Z\",\"fromMe\":false,"
                + "\"text\":\"On valide le budget IAM.\"}]}]},"
                + "\"cursors\":[{\"ref\":\"depot:abc\",\"kind\":\"RECORDING\",\"at\":\"2026-09-12T14:30:05Z\"}]}";

        postBatch(sync.getId(), token, recording).andExpect(status().isOk()).andExpect(jsonPath("$.cursors").value(1));

        assertThat(syncCursors.findByUserIdAndHostIdAndSourceAndConversationRef(alice.getId(), aliceA.hostId(),
                RadarSyncCursor.SOURCE_DEPOT, "depot:abc")).isPresent();
        assertThat(syncCursors.findByUserIdAndHostIdAndSourceAndConversationRef(alice.getId(), aliceA.hostId(),
                RadarSyncCursor.SOURCE_TEAMS, "depot:abc")).isEmpty();

        // Fin de la synchro, puis la suivante : depot_done porte l'enregistrement, et rien du poste B.
        mockMvc.perform(post("/api/runner/radar/syncs/" + sync.getId() + "/finish").contextPath("/api")
                .header(HEADER, token).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"SUCCEEDED\"}"))
                .andExpect(status().isOk());
        syncCursors.save(RadarSyncCursor.builder().userId(alice.getId()).hostId(aliceB.hostId())
                .source(RadarSyncCursor.SOURCE_DEPOT).conversationRef("depot:poste-b").cursorAt(OffsetDateTime.now()).build());
        org.mockito.Mockito.clearInvocations(router);
        launcher.start(aliceA, RadarSyncTrigger.MANUAL, null);
        org.mockito.ArgumentCaptor<com.fasterxml.jackson.databind.JsonNode> input =
                org.mockito.ArgumentCaptor.forClass(com.fasterxml.jackson.databind.JsonNode.class);
        org.mockito.Mockito.verify(router).call(any(fr.claudegateway.runner.channel.RunnerTarget.class),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.eq(RadarSyncLauncher.COLLECT),
                input.capture(), org.mockito.ArgumentMatchers.anyLong());
        assertThat(input.getValue().path("depot_done")).hasSize(1);
        assertThat(input.getValue().path("depot_done").get(0).asText()).isEqualTo("depot:abc");
        assertThat(input.getValue().path("cursors")).isEmpty();
    }

    @Test
    @DisplayName("Lot hors contrat : 400 et aucun curseur bougé ; curseur dans le futur : 400")
    void invalidBatchMovesNoCursor() throws Exception {
        RadarSync sync = running(aliceA);
        String token = runnerToken(aliceA);
        String noExchanges = "{\"batch\":{\"batchKey\":\"teams:vide\",\"exchanges\":[]},"
                + "\"cursors\":[{\"ref\":\"19:abc@thread.v2\",\"at\":\"2026-09-12T07:05:10Z\"}]}";

        postBatch(sync.getId(), token, noExchanges).andExpect(status().isBadRequest());
        assertThat(cursorOf(aliceA)).isNull();
        assertThat(analysisBatches.findAll()).isEmpty();

        postBatch(sync.getId(), token, "{\"cursors\":[{\"ref\":\"19:abc@thread.v2\",\"at\":\""
                + OffsetDateTime.now().plusDays(2) + "\"}]}").andExpect(status().isBadRequest());
        postBatch(sync.getId(), token, "{\"batch\":\"illisible\"}").andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Synchro close : 409 ; sans droit : 403 ; sans jeton : 401 — rien n'entre")
    void refusals() throws Exception {
        RadarSync sync = running(aliceA);
        String token = runnerToken(aliceA);

        postBatch(sync.getId(), null, body("teams:k2", "2026-09-12T07:05:10Z", "x")).andExpect(status().isUnauthorized());

        when(teamsAccess.hasAccess(alice.getId())).thenReturn(false);
        postBatch(sync.getId(), token, body("teams:k2", "2026-09-12T07:05:10Z", "x")).andExpect(status().isForbidden());
        when(teamsAccess.hasAccess(alice.getId())).thenReturn(true);

        mockMvc.perform(post("/api/runner/radar/syncs/" + sync.getId() + "/finish").contextPath("/api")
                .header(HEADER, token).contentType(MediaType.APPLICATION_JSON).content("{\"status\":\"PARTIAL\"}"))
                .andExpect(status().isOk());
        postBatch(sync.getId(), token, body("teams:k2", "2026-09-12T07:05:10Z", "x"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("PARTIAL"));

        assertThat(analysisBatches.findAll()).isEmpty();
        assertThat(cursorOf(aliceA)).isNull();
    }

    @Test
    @DisplayName("Isolation : le jeton du poste B d'Alice ou celui de Bob ne dépose ni lot ni curseur sur la synchro du poste A")
    void isolation() throws Exception {
        RadarSync sync = running(aliceA);

        postBatch(sync.getId(), runnerToken(aliceB), body("teams:k3", "2026-09-12T07:05:10Z", "x"))
                .andExpect(status().isNotFound());
        postBatch(sync.getId(), runnerToken(bobScope), body("teams:k3", "2026-09-12T07:05:10Z", "x"))
                .andExpect(status().isNotFound());

        assertThat(analysisBatches.findAll()).isEmpty();
        assertThat(syncCursors.findAll()).isEmpty();
    }
}
