package fr.claudegateway.radar;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

import com.jayway.jsonpath.JsonPath;

import fr.claudegateway.access.AccessCodeRepository;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRole;

/**
 * F-107 / SF-107-04 — <b>l'essai Vigie et sa mesure</b> : un code Vigie ouvre le Radar d'un compte sans option,
 * la réserve est celle de l'essai (première synchro hors réserve), et le PO lit le coût de chaque synchro.
 */
class VigieTrialAdminApiIntegrationTest extends RadarIntegrationTestBase {

    @Autowired private AccessCodeRepository accessCodes;
    @Autowired private JdbcTemplate jdbc;

    @AfterEach
    void cleanCodes() {
        accessCodes.deleteAll();
    }

    /** Une synchro du poste, déjà consommée, éventuellement hors réserve. */
    private RadarSync spent(RadarScope scope, long tokens, boolean exempt) {
        RadarSync sync = registry.startSync(scope);
        sync.setConsumedTokens(tokens);
        syncs.save(sync);
        jdbc.update("update radar_syncs set reserve_exempt = ? where id = ?", exempt, sync.getId());
        return syncs.findById(sync.getId()).orElseThrow();
    }

    @Test
    @DisplayName("Essai Vigie : Radar ouvert, réserve d'essai sans la première synchro, relevé ADMIN, 403 USER")
    void vigieTrialEndToEnd() throws Exception {
        accessCodes.deleteAll();
        User carol = seedUser("carol-trial@example.com", UserRole.USER);
        String carolToken = jwtService.generateToken(carol);
        RadarScope carolScope = new RadarScope(carol.getId(), seedHost(carol.getId(), "Client ACME"));

        // Sans droit : le Radar est fermé.
        mockMvc.perform(get(url(carolScope, "/reserve")).contextPath("/api").header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isForbidden());

        // Alice (ADMIN) émet un essai Vigie ; Carol le consomme.
        String issued = mockMvc.perform(post("/api/admin/access-codes").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"essai ACME\",\"space\":\"VIGIE\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String code = JsonPath.read(issued, "$.code");
        mockMvc.perform(post("/api/access-code/redeem").contextPath("/api")
                        .header("Authorization", "Bearer " + carolToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());

        spent(carolScope, 2_000_000, true);
        spent(carolScope, 700_000, false);

        mockMvc.perform(get(url(carolScope, "/reserve")).contextPath("/api").header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trial").value(true))
                .andExpect(jsonPath("$.monthlyTokens").value(3_000_000))
                .andExpect(jsonPath("$.consumedThisMonth").value(700_000))
                .andExpect(jsonPath("$.remainingThisMonth").value(2_300_000))
                .andExpect(jsonPath("$.resetsAt").doesNotExist());

        mockMvc.perform(get("/api/admin/vigie-trials").contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].label").value("essai ACME"))
                .andExpect(jsonPath("$[0].email").value("carol-trial@example.com"))
                .andExpect(jsonPath("$[0].active").value(true))
                .andExpect(jsonPath("$[0].syncCount").value(2))
                .andExpect(jsonPath("$[0].consumedTokens").value(2_700_000))
                .andExpect(jsonPath("$[0].syncs[0].firstSync").value(true))
                .andExpect(jsonPath("$[0].syncs[0].costUsd").exists())
                .andExpect(jsonPath("$[0].syncs[1].firstSync").value(false));

        mockMvc.perform(get("/api/admin/vigie-trials").contextPath("/api").header("Authorization", "Bearer " + carolToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/vigie-trials").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Isolation : la réserve d'un abonné ne compte pas la première synchro, et reste par poste")
    void subscriberReserveExcludesFirstSync() throws Exception {
        spent(aliceA, 2_500_000, true);
        spent(aliceA, 400_000, false);
        spent(aliceB, 900_000, false);

        mockMvc.perform(get(url(aliceA, "/reserve")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trial").value(false))
                .andExpect(jsonPath("$.consumedThisMonth").value(400_000));
    }
}
