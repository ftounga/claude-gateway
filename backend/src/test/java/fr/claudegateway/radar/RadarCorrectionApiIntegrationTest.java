package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import fr.claudegateway.radar.RadarRegistry.CommitmentInput;

/** F-99 / SF-99-02 — les corrections souveraines par l'API, de bout en bout. */
class RadarCorrectionApiIntegrationTest extends RadarIntegrationTestBase {

    @Test
    @DisplayName("« pas moi » retire l'engagement des listes, pas de la page du sujet ; annuler le rend")
    void notMineLeavesTheListsAndUndoBringsItBack() throws Exception {
        RadarEvidence p = proof(aliceA, "Quelqu'un peut envoyer le plan ?");
        RadarSubject subject = registry.createSubject(aliceA, "MFA", null, ids(p));
        RadarCommitment commitment = registry.recordCommitment(aliceA, new CommitmentInput(subject.getId(),
                RadarCommitmentDirection.ME_TO_OTHER, "Envoyer le plan", null, null, null, null, false,
                RadarCertainty.PROBABLE, null, ids(p)));

        String body = mockMvc.perform(post(url(aliceA, "/commitments/" + commitment.getId() + "/corrections"))
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"NOT_MINE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("NOT_MINE"))
                .andExpect(jsonPath("$.targetKind").value("COMMITMENT"))
                .andExpect(jsonPath("$.after.disowned").value(true))
                .andReturn().getResponse().getContentAsString();
        String correctionId = JsonPath.read(body, "$.id");

        mockMvc.perform(get(url(aliceA, "/commitments")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$", Matchers.hasSize(0)));
        mockMvc.perform(get(url(aliceA, "/commitments")).param("includeDisowned", "true").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].disowned").value(true))
                .andExpect(jsonPath("$[0].sovereign").value(true));
        mockMvc.perform(get(url(aliceA, "/subjects/" + subject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.commitments", Matchers.hasSize(1)));
        mockMvc.perform(get(url(aliceA, "/subjects")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$[0].openCommitments").value(0));

        mockMvc.perform(post(url(aliceA, "/corrections/" + correctionId + "/undo")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.undoneAt").isNotEmpty());
        mockMvc.perform(post(url(aliceA, "/corrections/" + correctionId + "/undo")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("radar_correction_conflict"));

        mockMvc.perform(get(url(aliceA, "/commitments")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].sovereign").value(false));
    }

    @Test
    @DisplayName("corriger un sujet : la page porte la marque, le journal la ligne")
    void subjectCorrectionIsVisibleAndJournaled() throws Exception {
        RadarSubject subject = registry.createSubject(aliceA, "MFA", null, ids(proof(aliceA, "MFA")));

        mockMvc.perform(post(url(aliceA, "/subjects/" + subject.getId() + "/corrections")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"SET_STATE\",\"state\":\"BLOCKED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.before.state").value("NEW"))
                .andExpect(jsonPath("$.after.state").value("BLOCKED"));

        mockMvc.perform(get(url(aliceA, "/subjects/" + subject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.state").value("BLOCKED"))
                .andExpect(jsonPath("$.stateSovereign").value(true))
                .andExpect(jsonPath("$.nameSovereign").value(false));

        mockMvc.perform(get(url(aliceA, "/corrections")).param("subjectId", subject.getId().toString())
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].action").value("SET_STATE"));
    }

    @Test
    void missingOrInvalidActionIsA400() throws Exception {
        RadarSubject subject = registry.createSubject(aliceA, "MFA", null, ids(proof(aliceA, "MFA")));

        mockMvc.perform(post(url(aliceA, "/subjects/" + subject.getId() + "/corrections")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_error"));

        mockMvc.perform(post(url(aliceA, "/subjects/" + subject.getId() + "/corrections")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"SET_STATE\",\"state\":\"CLOSED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("radar_invalid"));
    }

    @Test
    @DisplayName("ISOLATION : corriger le sujet d'un autre poste → 404, aucune ligne de journal")
    void correctingAnotherHostsSubjectIsA404() throws Exception {
        RadarSubject subject = registry.createSubject(aliceB, "CAGIP", null, ids(proof(aliceB, "CAGIP")));

        mockMvc.perform(post(url(aliceA, "/subjects/" + subject.getId() + "/corrections")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"RENAME\",\"name\":\"Piraté\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(url(bobScope, "/subjects/" + subject.getId() + "/corrections")).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"RENAME\",\"name\":\"Piraté\"}"))
                .andExpect(status().isNotFound());

        assertThat(corrections.findAll()).isEmpty();
        assertThat(subjects.findById(subject.getId()).orElseThrow().getName()).isEqualTo("CAGIP");
    }
}
