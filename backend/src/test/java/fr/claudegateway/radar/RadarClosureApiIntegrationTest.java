package fr.claudegateway.radar;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.radar.RadarRegistry.CommitmentInput;

/** F-99 / SF-99-04 — la clôture par l'API. */
class RadarClosureApiIntegrationTest extends RadarIntegrationTestBase {

    @Test
    @DisplayName("clore : engagements ouverts rendus, sujet hors liste, cherchable, 409 la seconde fois")
    void closeThroughTheApi() throws Exception {
        RadarEvidence p = proof(aliceA, "LDAP");
        RadarSubject ldap = registry.createSubject(aliceA, "LDAP", null, ids(p));
        registry.recordCommitment(aliceA, new CommitmentInput(ldap.getId(), RadarCommitmentDirection.ME_TO_OTHER,
                "Rendre l'accès", null, null, null, null, false, RadarCertainty.CERTAIN, null, ids(p)));

        mockMvc.perform(post(url(aliceA, "/subjects/" + ldap.getId() + "/close")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correction.action").value("CLOSE"))
                .andExpect(jsonPath("$.openCommitments", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.openCommitments[0].description").value("Rendre l'accès"));

        mockMvc.perform(get(url(aliceA, "/subjects")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$", Matchers.hasSize(0)));
        mockMvc.perform(get(url(aliceA, "/subjects")).param("q", "ldap").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].state").value("CLOSED"));
        mockMvc.perform(post(url(aliceA, "/subjects/" + ldap.getId() + "/close")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("radar_state_conflict"));
    }

    @Test
    @DisplayName("proposition : confirmer, refuser ; 409 sans proposition")
    void proposalThroughTheApi() throws Exception {
        RadarSubject a = registry.createSubject(aliceA, "A", null, ids(proof(aliceA, "A")));
        RadarSubject b = registry.createSubject(aliceA, "B", RadarSubjectState.WAITING, ids(proof(aliceA, "B")));

        mockMvc.perform(post(url(aliceA, "/subjects/" + a.getId() + "/close-proposal/confirm")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict());

        registry.proposeClosure(aliceA, a.getId(), ids(proof(aliceA, "on peut fermer A")));
        registry.proposeClosure(aliceA, b.getId(), ids(proof(aliceA, "on peut fermer B")));

        mockMvc.perform(get(url(aliceA, "/subjects/" + a.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.state").value("CLOSE_PROPOSED"))
                .andExpect(jsonPath("$.closeSignalEvidenceIds", Matchers.hasSize(1)));
        mockMvc.perform(post(url(aliceA, "/subjects/" + a.getId() + "/close-proposal/confirm")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correction.action").value("CONFIRM_CLOSE"));
        mockMvc.perform(post(url(aliceA, "/subjects/" + b.getId() + "/close-proposal/reject")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("REJECT_CLOSE"))
                .andExpect(jsonPath("$.after.state").value("WAITING"));
    }

    @Test
    @DisplayName("réveil : listé avec awake, écarté par wake/dismiss")
    void wakeThroughTheApi() throws Exception {
        RadarSubject ldap = registry.createSubject(aliceA, "LDAP", null, ids(proof(aliceA, "LDAP")));
        mockMvc.perform(post(url(aliceA, "/subjects/" + ldap.getId() + "/close")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
        registry.attachEvidence(aliceA, ldap.getId(),
                ids(proof(aliceA, "Ça recommence", OffsetDateTime.now().plusSeconds(1))));

        mockMvc.perform(get(url(aliceA, "/subjects")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].awake").value(true));
        mockMvc.perform(post(url(aliceA, "/subjects/" + ldap.getId() + "/wake/dismiss")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.action").value("DISMISS_WAKE"));
        mockMvc.perform(get(url(aliceA, "/subjects")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$", Matchers.hasSize(0)));
    }

    @Test
    @DisplayName("ISOLATION : clore depuis le poste de Bob le sujet d'Alice → 404")
    void closingAnotherUsersSubjectIsA404() throws Exception {
        RadarSubject ldap = registry.createSubject(aliceA, "LDAP", null, ids(proof(aliceA, "LDAP")));

        mockMvc.perform(post(url(bobScope, "/subjects/" + ldap.getId() + "/close")).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }
}
