package fr.claudegateway.radar;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.host.ClientSpace;

/** F-103 / SF-103-02 — ce que le Radar ne sait pas, de bout en bout. */
class RadarUnknownsApiIntegrationTest extends RadarIntegrationTestBase {

    @Test
    @DisplayName("les manques d'un sujet, adressés au pilote désigné")
    void unknownsGoToTheDriver() throws Exception {
        RadarEvidence p = proof(aliceA, "Sophie pilote le MFA.");
        RadarSubject mfa = registry.createSubject(aliceA, "MFA", RadarSubjectState.ADVANCING, ids(p));
        RadarPerson sophie = registry.upsertPerson(aliceA, "sophie@client.fr", "Sophie", "Cheffe de projet");
        registry.assignRole(aliceA, mfa.getId(), sophie.getId(), RadarRole.DRIVES, ids(p));

        mockMvc.perform(get(url(aliceA, "/subjects/" + mfa.getId() + "/unknowns")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(3)))
                .andExpect(jsonPath("$[0].kind").value("NEXT_STEP"))
                .andExpect(jsonPath("$[0].question").value("La prochaine étape n'est pas connue."))
                .andExpect(jsonPath("$[0].ask.displayName").value("Sophie"))
                .andExpect(jsonPath("$[0].ask.jobTitle").value("Cheffe de projet"))
                .andExpect(jsonPath("$[0].ask.role").value("DRIVES"))
                .andExpect(jsonPath("$[0].ask.reason").value("pilote le sujet"))
                .andExpect(jsonPath("$[1].kind").value("DUE_DATE"))
                .andExpect(jsonPath("$[2].kind").value("DECIDER"));
    }

    @Test
    @DisplayName("une synchro partielle est dite en tête")
    void partialSyncComesFirst() throws Exception {
        RadarSubject mfa = registry.createSubject(aliceA, "MFA", RadarSubjectState.ADVANCING, ids(proof(aliceA, "MFA")));
        RadarSync sync = registry.startSync(aliceA);
        registry.finishSync(aliceA, sync.getId(), RadarSyncStatus.PARTIAL, null, 0);

        mockMvc.perform(get(url(aliceA, "/subjects/" + mfa.getId() + "/unknowns")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].kind").value("COVERAGE"))
                .andExpect(jsonPath("$[0].ask").value(Matchers.nullValue()));
    }

    @Test
    @DisplayName("ISOLATION : ni sous un autre poste d'Alice, ni pour Bob ; hors Vigie 409 ; identifiant malformé 400")
    void isolation() throws Exception {
        RadarSubject mfa = registry.createSubject(aliceA, "MFA", null, ids(proof(aliceA, "MFA")));

        mockMvc.perform(get(url(aliceB, "/subjects/" + mfa.getId() + "/unknowns")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(url(aliceA, "/subjects/" + mfa.getId() + "/unknowns")).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(url(aliceA, "/subjects/pas-un-uuid/unknowns")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest());

        hostSpaces.findAll().stream()
                .filter(s -> s.getHostId().equals(aliceA.hostId()) && s.getSpace() == ClientSpace.VIGIE)
                .forEach(hostSpaces::delete);
        mockMvc.perform(get(url(aliceA, "/subjects/" + mfa.getId() + "/unknowns")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict());
    }
}
