package fr.claudegateway.radar;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Le Radar est une API <b>de la Vigie</b> (F-106 / SF-106-01) : un client retiré de la Vigie n'y est
 * plus lisible, mais son export et sa purge restent possibles.
 */
class RadarVigieSpaceApiIntegrationTest extends RadarIntegrationTestBase {

    @Test
    @DisplayName("retiré de la Vigie : lecture et vérification refusées (409), export et purge servis")
    void aHostRemovedFromTheVigieIsNoLongerReadable() throws Exception {
        registry.createSubject(aliceA, "MFA", null, ids(proof(aliceA, "MFA")));
        mockMvc.perform(get(url(aliceA, "/subjects")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/runner-hosts/" + aliceA.hostId() + "/spaces/VIGIE").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());

        mockMvc.perform(get(url(aliceA, "/subjects")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("host_not_in_space"));
        mockMvc.perform(get(url(aliceA, "/verification")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict());
        mockMvc.perform(get(url(aliceA, "/export")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
        mockMvc.perform(post(url(aliceA, "/purge")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"VIGIE_REMOVED\",\"confirm\":true}"))
                .andExpect(status().isOk());
        // L'autre poste d'Alice, lui, reste lisible.
        mockMvc.perform(get(url(aliceB, "/subjects")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("isolation : le poste d'autrui reste introuvable, avant toute question d'espace")
    void anotherUsersHostStaysNotFound() throws Exception {
        mockMvc.perform(get(url(bobScope, "/subjects")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }
}
