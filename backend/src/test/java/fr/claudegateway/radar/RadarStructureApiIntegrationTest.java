package fr.claudegateway.radar;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

/** F-99 / SF-99-03 — fusion, séparation et alias par l'API, de bout en bout. */
class RadarStructureApiIntegrationTest extends RadarIntegrationTestBase {

    @Test
    @DisplayName("fusionner, lire, annuler par la route du journal")
    void mergeReadAndUndo() throws Exception {
        RadarSubject mfa = registry.createSubject(aliceA, "MFA", null, ids(proof(aliceA, "MFA")));
        RadarSubject presta = registry.createSubject(aliceA, "Double auth", null, ids(proof(aliceA, "2FA")));

        String body = mockMvc.perform(post(url(aliceA, "/subjects/" + presta.getId() + "/merge")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intoSubjectId\":\"" + mfa.getId() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("MERGE"))
                .andReturn().getResponse().getContentAsString();
        String correctionId = JsonPath.read(body, "$.id");

        mockMvc.perform(get(url(aliceA, "/subjects")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("MFA"));
        mockMvc.perform(get(url(aliceA, "/subjects/" + mfa.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.aliases[0].alias").value("Double auth"))
                .andExpect(jsonPath("$.aliases[0].origin").value("MERGE"))
                .andExpect(jsonPath("$.chronology", Matchers.hasSize(2)));
        mockMvc.perform(get(url(aliceA, "/subjects/" + presta.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.mergedIntoId").value(mfa.getId().toString()));
        mockMvc.perform(post(url(aliceA, "/subjects/" + presta.getId() + "/corrections")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"RENAME\",\"name\":\"X\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("radar_subject_merged"));

        mockMvc.perform(post(url(aliceA, "/corrections/" + correctionId + "/undo")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
        mockMvc.perform(get(url(aliceA, "/subjects")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$", Matchers.hasSize(2)));
    }

    @Test
    @DisplayName("séparer : 400 sans preuve, 200 avec, le nouveau sujet est listé")
    void split() throws Exception {
        RadarEvidence a = proof(aliceA, "MFA");
        RadarEvidence b = proof(aliceA, "Okta");
        RadarSubject mfa = registry.createSubject(aliceA, "MFA", null, ids(a, b));

        mockMvc.perform(post(url(aliceA, "/subjects/" + mfa.getId() + "/split")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Okta\",\"evidenceIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("radar_invalid"));

        mockMvc.perform(post(url(aliceA, "/subjects/" + mfa.getId() + "/split")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Okta\",\"evidenceIds\":[\"" + b.getId() + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("SPLIT"))
                .andExpect(jsonPath("$.after.created").isNotEmpty());

        mockMvc.perform(get(url(aliceA, "/subjects")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$", Matchers.hasSize(2)));
    }

    @Test
    void aliasesByHand() throws Exception {
        RadarSubject mfa = registry.createSubject(aliceA, "MFA", null, ids(proof(aliceA, "MFA")));

        String body = mockMvc.perform(post(url(aliceA, "/subjects/" + mfa.getId() + "/aliases")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alias\":\"Chantier Okta\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.origin").value("USER"))
                .andReturn().getResponse().getContentAsString();
        String aliasId = JsonPath.read(body, "$.id");

        mockMvc.perform(delete(url(aliceA, "/subjects/" + mfa.getId() + "/aliases/" + aliasId)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(delete(url(aliceA, "/subjects/" + mfa.getId() + "/aliases/" + aliasId)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("SF-99-06 : ajout et retrait d'alias journalisés, annulés par la route du journal ; autre poste → 404")
    void aliasGesturesAreUndoneFromTheJournal() throws Exception {
        RadarSubject mfa = registry.createSubject(aliceA, "MFA", null, ids(proof(aliceA, "MFA")));

        String body = mockMvc.perform(post(url(aliceA, "/subjects/" + mfa.getId() + "/aliases")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"alias\":\"Chantier Okta\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String aliasId = JsonPath.read(body, "$.id");

        String journal = mockMvc.perform(get(url(aliceA, "/corrections")).contextPath("/api")
                        .param("subjectId", mfa.getId().toString())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].action").value("ADD_ALIAS"))
                .andExpect(jsonPath("$[0].after.alias").value("Chantier Okta"))
                .andReturn().getResponse().getContentAsString();
        String addedId = JsonPath.read(journal, "$[0].id");

        // Le poste B d'Alice n'annule pas une correction du poste A.
        mockMvc.perform(post(url(aliceB, "/corrections/" + addedId + "/undo")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete(url(aliceA, "/subjects/" + mfa.getId() + "/aliases/" + aliasId)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
        journal = mockMvc.perform(get(url(aliceA, "/corrections")).contextPath("/api")
                        .param("subjectId", mfa.getId().toString())
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$[0].action").value("REMOVE_ALIAS"))
                .andExpect(jsonPath("$[0].before.alias").value("Chantier Okta"))
                .andReturn().getResponse().getContentAsString();
        String removedId = JsonPath.read(journal, "$[0].id");

        // L'ajout ne s'annule plus : l'alias a été retiré depuis.
        mockMvc.perform(post(url(aliceA, "/corrections/" + addedId + "/undo")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("radar_correction_conflict"));

        mockMvc.perform(post(url(aliceA, "/corrections/" + removedId + "/undo")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.undoneAt").isNotEmpty());
        mockMvc.perform(get(url(aliceA, "/subjects/" + mfa.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.aliases", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.aliases[0].alias").value("Chantier Okta"))
                .andExpect(jsonPath("$.aliases[0].origin").value("USER"));
    }

    @Test
    @DisplayName("ISOLATION : fusion vers le sujet d'un autre poste → 404")
    void mergeIntoAnotherHostIsA404() throws Exception {
        RadarSubject mine = registry.createSubject(aliceA, "MFA", null, ids(proof(aliceA, "MFA")));
        RadarSubject other = registry.createSubject(aliceB, "MFA", null, ids(proof(aliceB, "MFA")));

        mockMvc.perform(post(url(aliceA, "/subjects/" + mine.getId() + "/merge")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"intoSubjectId\":\"" + other.getId() + "\"}"))
                .andExpect(status().isNotFound());
    }
}
