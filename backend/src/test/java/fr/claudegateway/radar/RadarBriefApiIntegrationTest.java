package fr.claudegateway.radar;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-102 / SF-102-01 — le résumé du matin par l'API, poste par poste. */
class RadarBriefApiIntegrationTest extends RadarIntegrationTestBase {

    @Test
    @DisplayName("GET /brief : phrases, compteurs, synchro partielle dite en tête — et rien d'un autre poste")
    void briefOfOneHost() throws Exception {
        RadarEvidence proof = proof(aliceA, "Je te présente Karim jeudi.");
        RadarSubject subject = registry.createSubject(aliceA, "Accès réseau pour Sophie", null, ids(proof));
        registry.recordCommitment(aliceA, new RadarRegistry.CommitmentInput(subject.getId(),
                RadarCommitmentDirection.ME_TO_OTHER, "Présenter Sophie à Karim", null, null, null,
                LocalDate.now(ZoneOffset.UTC).minusDays(2), false, RadarCertainty.CERTAIN, null, ids(proof)));
        RadarSync sync = registry.startSync(aliceA);
        registry.finishSync(aliceA, sync.getId(), RadarSyncStatus.PARTIAL,
                "{\"conversations\":{\"active\":3,\"read\":2,\"partial\":1},\"messages\":40}", 0);

        // Le poste CAGIP d'Alice et celui de Bob ont leurs propres engagements : rien ne doit fuir.
        RadarEvidence other = proof(aliceB, "Autre client");
        RadarSubject cagip = registry.createSubject(aliceB, "Sujet CAGIP", null, ids(other));
        registry.recordCommitment(aliceB, new RadarRegistry.CommitmentInput(cagip.getId(),
                RadarCommitmentDirection.ME_TO_OTHER, "Engagement CAGIP", null, null, null, null, false,
                RadarCertainty.CERTAIN, null, ids(other)));
        registry.createSubject(bobScope, "Sujet de Bob", null, ids(proof(bobScope, "Bob")));

        mockMvc.perform(get(url(aliceA, "/brief")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentences", hasSize(2)))
                .andExpect(jsonPath("$.sentences[0].kind").value("OVERDUE"))
                .andExpect(jsonPath("$.sentences[0].text", startsWith("Vous deviez « Présenter Sophie à Karim »")))
                .andExpect(jsonPath("$.sentences[1].kind").value("NEW_SUBJECTS"))
                .andExpect(jsonPath("$.sentences[1].subjectId").value(subject.getId().toString()))
                .andExpect(jsonPath("$.counts.toDoByMe").value(1))
                .andExpect(jsonPath("$.counts.subjectsFollowed").value(1))
                .andExpect(jsonPath("$.counts.toHandle").value(1))
                .andExpect(jsonPath("$.running").doesNotExist())
                .andExpect(jsonPath("$.lastSync.status").value("PARTIAL"))
                .andExpect(jsonPath("$.coverageComplete").value(false))
                .andExpect(jsonPath("$.coverageWarning", startsWith("Synchro partielle")))
                .andExpect(jsonPath("$.coverageLines[0].source").value("TEAMS"))
                .andExpect(jsonPath("$.coverageLines[0].ok").value(false))
                .andExpect(jsonPath("$.coverageLines[0].text").value("Teams · 2 fils lus sur 3 actifs, 40 messages"));

        mockMvc.perform(get(url(aliceB, "/brief")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.counts.toDoByMe").value(1))
                .andExpect(jsonPath("$.counts.toHandle").value(0))
                .andExpect(jsonPath("$.lastSync").doesNotExist())
                .andExpect(jsonPath("$.coverageWarning", startsWith("Aucune synchro encore")));
    }

    @Test
    @DisplayName("isolation : 404 sur le poste d'autrui, 409 pour un client retiré de la Vigie")
    void isolation() throws Exception {
        mockMvc.perform(get(url(aliceA, "/brief")).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/runner-hosts/" + aliceA.hostId() + "/spaces/VIGIE").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
        mockMvc.perform(get(url(aliceA, "/brief")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("host_not_in_space"));
    }
}
