package fr.claudegateway.radar;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-99 / SF-99-01 — <b>le Radar d'EDENRED ne voit jamais celui de CAGIP</b> (cadrage §3).
 *
 * <p>Deux postes du même utilisateur, et deux utilisateurs : aucune lecture ne traverse la
 * frontière, et une tentative rend « introuvable », jamais « interdit ».</p>
 */
class RadarIsolationIntegrationTest extends RadarIntegrationTestBase {

    @Test
    @DisplayName("deux postes du même utilisateur : listes disjointes, sujet de l'autre poste introuvable")
    void twoHostsOfTheSameUserNeverMeet() throws Exception {
        RadarSubject edenred = registry.createSubject(aliceA, "MFA EDENRED", null, ids(proof(aliceA, "A")));
        RadarSubject cagip = registry.createSubject(aliceB, "MFA CAGIP", null, ids(proof(aliceB, "B")));
        RadarEvidence cagipProof = proof(aliceB, "Preuve CAGIP");

        mockMvc.perform(get(url(aliceA, "/subjects")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("MFA EDENRED"));

        mockMvc.perform(get(url(aliceA, "/subjects/" + cagip.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));

        mockMvc.perform(get(url(aliceA, "/evidence/" + cagipProof.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(url(aliceB, "/subjects/" + edenred.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("deux utilisateurs : le poste d'autrui est introuvable, et son sujet aussi")
    void anotherUsersHostIsNotFound() throws Exception {
        RadarSubject secret = registry.createSubject(aliceA, "Réorganisation", null, ids(proof(aliceA, "chut")));

        mockMvc.perform(get(url(aliceA, "/subjects")).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));

        mockMvc.perform(get(url(bobScope, "/subjects/" + secret.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(url(bobScope, "/people")).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(0)));
    }
}
