package fr.claudegateway.radar;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-101 / SF-101-04 — les relances dues, lues par poste, et seulement par lui. */
class RadarFollowUpApiIntegrationTest extends RadarIntegrationTestBase {

    private RadarCommitment owed(RadarScope scope, String what, OffsetDateTime at) {
        RadarEvidence proof = proof(scope, what, at);
        RadarSubject subject = registry.createSubject(scope, "Sujet " + what, null, ids(proof));
        RadarPerson marc = registry.upsertPerson(scope, "marc@client.fr", "Marc Durand", null);
        return registry.recordCommitment(scope, new RadarRegistry.CommitmentInput(subject.getId(),
                RadarCommitmentDirection.OTHER_TO_ME, what, marc.getId(), null, null, null, false,
                RadarCertainty.CERTAIN, null, ids(proof)));
    }

    @Test
    @DisplayName("followUpDue=true ne rend que les relances échues du poste ; tenue, la relance disparaît")
    void onlyDueFollowUps() throws Exception {
        RadarCommitment late = owed(aliceA, "Le devis", OffsetDateTime.now().minusDays(10));
        owed(aliceA, "Le planning", OffsetDateTime.now().minusMinutes(5));
        owed(aliceB, "La licence", OffsetDateTime.now().minusDays(10));

        mockMvc.perform(get(url(aliceA, "/commitments")).param("followUpDue", "true").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].description").value("Le devis"))
                .andExpect(jsonPath("$[0].followUpDue").value(true))
                .andExpect(jsonPath("$[0].followUpDueOn").isNotEmpty())
                .andExpect(jsonPath("$[0].lastEvidenceAt").isNotEmpty());

        mockMvc.perform(get(url(aliceA, "/commitments")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$", hasSize(2)));

        registry.markCommitment(aliceA, late.getId(), RadarCommitmentStatus.KEPT,
                ids(proof(aliceA, "Devis reçu, merci.")));
        mockMvc.perform(get(url(aliceA, "/commitments")).param("followUpDue", "true").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$", hasSize(0)));

        mockMvc.perform(get(url(aliceB, "/commitments")).param("followUpDue", "true").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].description").value("La licence"));
    }

    @Test
    @DisplayName("Isolation et validation : 404 pour autrui, 400 sur une valeur invalide")
    void isolationAndValidation() throws Exception {
        owed(aliceA, "Le devis", OffsetDateTime.now().minusDays(10));
        mockMvc.perform(get(url(aliceA, "/commitments")).param("followUpDue", "true").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(url(aliceA, "/commitments")).param("followUpDue", "peut-etre").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest());
    }
}
