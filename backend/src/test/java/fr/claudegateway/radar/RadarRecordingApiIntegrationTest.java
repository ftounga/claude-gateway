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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.radar.sync.RadarRecordingDepositService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;

/** F-104 / SF-104-04 — déposer un enregistrement : relais par morceaux vers un runner simulé. */
class RadarRecordingApiIntegrationTest extends RadarSyncIntegrationTestBase {

    @org.springframework.beans.factory.annotation.Autowired
    private fr.claudegateway.teams.meeting.MeetingRepository meetings;

    private final ObjectMapper mapper = new ObjectMapper();
    /** Ce que le poste simulé a reçu, par dépôt. */
    private final Map<String, ByteArrayOutputStream> received = new HashMap<>();
    private final Map<String, JsonNode> opened = new HashMap<>();
    private final Map<String, UUID> targets = new HashMap<>();
    /** F-147 / SF-147-01 : les transcriptions lancées par {@code finish}, et le nombre de demandes reçues. */
    private final Map<String, Integer> transcribing = new HashMap<>();
    /** F-147 / SF-147-02 : la réunion que la gateway a annoncée au poste, par dépôt. */
    private final Map<String, String> attached = new HashMap<>();

    /** Le sujet auquel tout dépôt de ce test appartient (F-147 / SF-147-02). */
    private UUID subjectId;
    private UUID bobSubjectId;

    @BeforeEach
    void fakeRunner() {
        received.clear();
        opened.clear();
        targets.clear();
        transcribing.clear();
        attached.clear();
        subjectId = registry.createSubject(aliceA, "Migration MFA", null, ids(proof(aliceA, "MFA"))).getId();
        bobSubjectId = registry.createSubject(bobScope, "Ailleurs", null, ids(proof(bobScope, "ailleurs"))).getId();
        when(liveness.isAlive(any(UUID.class), any(UUID.class))).thenReturn(true);
        when(router.call(any(RunnerTarget.class), anyString(), eq(RadarRecordingDepositService.DEPOSIT), any(), anyLong()))
                .thenAnswer(invocation -> {
                    RunnerTarget target = invocation.getArgument(0);
                    JsonNode input = invocation.getArgument(3);
                    String id = input.path("upload_id").asText();
                    targets.put(id, target.hostId());
                    return switch (input.path("op").asText()) {
                        case "open" -> {
                            opened.put(id, input);
                            received.put(id, new ByteArrayOutputStream());
                            yield ok("{\"accepted\":true,\"received\":0}");
                        }
                        case "chunk" -> {
                            ByteArrayOutputStream bytes = received.get(id);
                            if (bytes == null) {
                                yield ok("{\"accepted\":false,\"reason\":\"UNKNOWN_UPLOAD\",\"sentence\":\"inconnu\"}");
                            }
                            if (input.path("offset").asLong() != bytes.size()) {
                                yield ok("{\"accepted\":false,\"reason\":\"OFFSET_MISMATCH\",\"sentence\":\"Le poste a reçu "
                                        + bytes.size() + " octet(s)\"}");
                            }
                            bytes.writeBytes(Base64.getDecoder().decode(input.path("data").asText()));
                            yield ok("{\"accepted\":true,\"received\":" + bytes.size() + "}");
                        }
                        case "finish" -> {
                            transcribing.put(id, 0);
                            yield ok("{\"deposited\":true,\"file_name\":\"" + opened.get(id).path("file_name").asText()
                                    + "\",\"title\":\"" + opened.get(id).path("title").asText() + "\",\"recorded_at\":\""
                                    + opened.get(id).path("recorded_at").asText() + "\",\"size\":" + received.get(id).size()
                                    + ",\"transcription\":\"started\",\"job_id\":\"" + id
                                    + "\",\"phase\":\"AUDIO\",\"phase_label\":\"j'extrais le son\""
                                    + ",\"subject_id\":\"" + opened.get(id).path("subject_id").asText() + "\"}");
                        }
                        // F-147 / SF-147-01 : le poste dit où en est la transcription ; ici, deux demandes puis fini.
                        case "status" -> {
                            Integer asked = transcribing.get(id);
                            if (asked == null) {
                                yield ok("{\"known\":false}");
                            }
                            transcribing.put(id, asked + 1);
                            yield asked == 0
                                    ? ok("{\"known\":true,\"job_id\":\"" + id + "\",\"phase\":\"TRANSCRIPTION\","
                                            + "\"phase_label\":\"je transcris, ici\",\"over\":false}")
                                    : ok("{\"known\":true,\"job_id\":\"" + id + "\",\"phase\":\"TERMINE\","
                                            + "\"phase_label\":\"terminé\",\"over\":true}");
                        }
                        case "attach" -> {
                            attached.put(id, input.path("meeting_id").asText());
                            yield ok("{\"attached\":true}");
                        }
                        default -> {
                            received.remove(id);
                            yield ok("{\"aborted\":true}");
                        }
                    };
                });
    }

    private String open(RadarScope scope, String token, String body, int expected) throws Exception {
        return mockMvc.perform(post(url(scope, "/recordings")).contextPath("/api")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().is(expected))
                .andReturn().getResponse().getContentAsString();
    }

    private String request(String name, long size) {
        return request(name, size, subjectId);
    }

    private static String request(String name, long size, UUID subject) {
        return "{\"fileName\":\"" + name + "\",\"sizeBytes\":" + size + ",\"title\":\"Atelier sécurité\","
                + "\"recordedAt\":\"2026-09-12T10:00:00+02:00\""
                + (subject == null ? "" : ",\"subjectId\":\"" + subject + "\"") + "}";
    }

    @Test
    @DisplayName("nominal : ouvrir, deux morceaux relayés encodés, finir ; morceaux non journalisés")
    void nominal() throws Exception {
        JsonNode openedView = mapper.readTree(open(aliceA, aliceToken, request("salle B.m4a", 700_000), 201));
        String uploadId = openedView.path("uploadId").asText();
        assertThat(openedView.path("chunkBytes").asInt()).isEqualTo(RadarRecordingDepositService.CHUNK_BYTES);
        long auditsAfterOpen = runnerAudits.count();

        byte[] first = new byte[RadarRecordingDepositService.CHUNK_BYTES];
        byte[] second = new byte[700_000 - first.length];
        java.util.Arrays.fill(first, (byte) 7);
        java.util.Arrays.fill(second, (byte) 9);
        mockMvc.perform(put(url(aliceA, "/recordings/" + uploadId + "/chunks")).contextPath("/api").param("offset", "0")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(first))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.received").value(first.length));
        mockMvc.perform(put(url(aliceA, "/recordings/" + uploadId + "/chunks")).contextPath("/api")
                        .param("offset", String.valueOf(first.length))
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(second))
                .andExpect(status().isOk());
        assertThat(runnerAudits.count()).isEqualTo(auditsAfterOpen);

        mockMvc.perform(post(url(aliceA, "/recordings/" + uploadId + "/finish")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("salle B.m4a"))
                .andExpect(jsonPath("$.title").value("Atelier sécurité"))
                .andExpect(jsonPath("$.sizeBytes").value(700_000))
                .andExpect(jsonPath("$.transcription").value("started"))
                .andExpect(jsonPath("$.jobId").value(uploadId))
                .andExpect(jsonPath("$.phaseLabel").value("j'extrais le son"));

        byte[] all = received.get(uploadId).toByteArray();
        assertThat(all).hasSize(700_000);
        assertThat(all[0]).isEqualTo((byte) 7);
        assertThat(all[699_999]).isEqualTo((byte) 9);
        assertThat(targets.get(uploadId)).isEqualTo(aliceA.hostId());
        assertThat(runnerAudits.count()).isGreaterThan(auditsAfterOpen);
    }

    @Test
    @DisplayName("validation : extension, taille, titre, date, morceau trop gros ou vide, offset négatif → 400 sans runner")
    void validation() throws Exception {
        open(aliceA, aliceToken, request("rapport.pdf", 10), 400);
        open(aliceA, aliceToken, request("a.mp3", RadarRecordingDepositService.MAX_BYTES + 1), 400);
        open(aliceA, aliceToken, "{\"fileName\":\"a.mp3\",\"sizeBytes\":10,\"title\":\" \",\"recordedAt\":\"2026-09-12T10:00:00+02:00\"}", 400);
        open(aliceA, aliceToken, "{\"fileName\":\"a.mp3\",\"sizeBytes\":10,\"title\":\"x\",\"recordedAt\":\"hier\"}", 400);
        // F-147 / SF-147-02 : sans sujet, rien ne part ; un sujet d'un autre compte est introuvable.
        open(aliceA, aliceToken, request("a.mp3", 10, null), 400);
        open(aliceA, aliceToken, request("a.mp3", 10, bobSubjectId), 404);
        String id = UUID.randomUUID().toString();
        mockMvc.perform(put(url(aliceA, "/recordings/" + id + "/chunks")).contextPath("/api").param("offset", "0")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[RadarRecordingDepositService.CHUNK_BYTES + 1]))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(url(aliceA, "/recordings/" + id + "/chunks")).contextPath("/api").param("offset", "-1")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[3]))
                .andExpect(status().isBadRequest());
        verify(router, never()).call(any(RunnerTarget.class), anyString(), eq(RadarRecordingDepositService.DEPOSIT), any(), anyLong());
    }

    @Test
    @DisplayName("refus du poste : offset faux et inconnu → 409 / 404 ; volet désactivé → 409 ; runner muet → 409")
    void runnerRefusals() throws Exception {
        String uploadId = mapper.readTree(open(aliceA, aliceToken, request("a.mp3", 10), 201)).path("uploadId").asText();
        mockMvc.perform(put(url(aliceA, "/recordings/" + uploadId + "/chunks")).contextPath("/api").param("offset", "4")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[3]))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Le poste a reçu 0 octet(s)"));
        mockMvc.perform(put(url(aliceA, "/recordings/" + UUID.randomUUID() + "/chunks")).contextPath("/api").param("offset", "0")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[3]))
                .andExpect(status().isNotFound());

        when(router.call(any(RunnerTarget.class), anyString(), eq(RadarRecordingDepositService.DEPOSIT), any(), anyLong()))
                .thenReturn(ok("{\"accepted\":false,\"reason\":\"TEAMS_DISABLED\",\"sentence\":\"désactivé\"}"));
        open(aliceA, aliceToken, request("a.mp3", 10), 409);
        when(router.call(any(RunnerTarget.class), anyString(), eq(RadarRecordingDepositService.DEPOSIT), any(), anyLong()))
                .thenReturn(RunnerCallResult.backendError(RunnerErrorCodes.RUNNER_TIMEOUT));
        open(aliceA, aliceToken, request("a.mp3", 10), 409);
    }

    @Test
    @DisplayName("F-147 : l'avancement de la transcription se lit en toutes lettres, jusqu'à la fin ; inconnu → known=false")
    void transcriptionProgress() throws Exception {
        String uploadId = mapper.readTree(open(aliceA, aliceToken, request("salle B.m4a", 3), 201)).path("uploadId").asText();
        mockMvc.perform(put(url(aliceA, "/recordings/" + uploadId + "/chunks")).contextPath("/api").param("offset", "0")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[3]))
                .andExpect(status().isOk());
        mockMvc.perform(post(url(aliceA, "/recordings/" + uploadId + "/finish")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());

        mockMvc.perform(get(url(aliceA, "/recordings/" + uploadId + "/status")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.known").value(true))
                .andExpect(jsonPath("$.over").value(false))
                .andExpect(jsonPath("$.phaseLabel").value("je transcris, ici"));
        mockMvc.perform(get(url(aliceA, "/recordings/" + uploadId + "/status")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.over").value(true))
                .andExpect(jsonPath("$.phaseLabel").value("terminé"));

        // Un dépôt jamais terminé n'a pas de travail : on le dit, on ne l'invente pas.
        mockMvc.perform(get(url(aliceA, "/recordings/" + UUID.randomUUID() + "/status")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.known").value(false));

        // ISOLATION : le poste d'un autre compte n'est pas interrogeable.
        mockMvc.perform(get(url(bobScope, "/recordings/" + uploadId + "/status")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("F-147 : finir un dépôt crée LA RÉUNION du sujet choisi, et le poste sait où déposer le texte")
    void theDepositBecomesAMeeting() throws Exception {
        String uploadId = mapper.readTree(open(aliceA, aliceToken, request("salle B.m4a", 3), 201))
                .path("uploadId").asText();
        mockMvc.perform(put(url(aliceA, "/recordings/" + uploadId + "/chunks")).contextPath("/api").param("offset", "0")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(new byte[3]))
                .andExpect(status().isOk());

        String body = mockMvc.perform(post(url(aliceA, "/recordings/" + uploadId + "/finish")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meetingId").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        UUID meetingId = UUID.fromString(mapper.readTree(body).path("meetingId").asText());

        // La réunion porte le sujet choisi au geste, le titre et la date du dépôt — et aucune URL.
        fr.claudegateway.teams.meeting.Meeting meeting = meetings.findById(meetingId).orElseThrow();
        assertThat(meeting.getSubjectId()).isEqualTo(subjectId);
        assertThat(meeting.getUserId()).isEqualTo(aliceA.userId());
        assertThat(meeting.getHostId()).isEqualTo(aliceA.hostId());
        assertThat(meeting.getTitle()).isEqualTo("Atelier sécurité");
        assertThat(meeting.getMeetingUrl()).isNull();
        assertThat(meeting.getTranscriptStatus())
                .isEqualTo(fr.claudegateway.teams.meeting.TranscriptStatus.PENDING);
        // Et le poste a reçu l'adresse où déposer le texte au terme de la transcription.
        assertThat(attached.get(uploadId)).isEqualTo(meetingId.toString());
    }

    @Test
    @DisplayName("ISOLATION : poste hors ligne → 409 ; autre compte → 404 ; hors Vigie → 409 ; aucun appel au runner")
    void isolationAndOffline() throws Exception {
        when(liveness.isAlive(any(UUID.class), any(UUID.class))).thenReturn(false);
        open(aliceA, aliceToken, request("a.mp3", 10), 409);
        when(liveness.isAlive(any(UUID.class), any(UUID.class))).thenReturn(true);

        open(bobScope, aliceToken, request("a.mp3", 10), 404);
        hostSpaces.deleteAll(hostSpaces.findAll().stream()
                .filter(s -> s.getHostId().equals(aliceB.hostId()) && s.getSpace() == fr.claudegateway.runner.host.ClientSpace.VIGIE)
                .toList());
        open(aliceB, aliceToken, request("a.mp3", 10), 409);
        mockMvc.perform(delete(url(bobScope, "/recordings/" + UUID.randomUUID())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
        verify(router, never()).call(any(RunnerTarget.class), anyString(), eq(RadarRecordingDepositService.DEPOSIT), any(), anyLong());

        String uploadId = mapper.readTree(open(aliceA, aliceToken, request("a.mp3", 10), 201)).path("uploadId").asText();
        mockMvc.perform(delete(url(aliceA, "/recordings/" + uploadId)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
        assertThat(received).doesNotContainKey(uploadId);
    }
}
