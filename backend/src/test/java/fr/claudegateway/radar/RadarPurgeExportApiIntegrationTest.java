package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import fr.claudegateway.radar.RadarRegistry.CommitmentInput;
import fr.claudegateway.radar.RadarRegistry.SummarySentence;
import fr.claudegateway.runner.host.HostMissionStatus;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRole;

/** F-99 / SF-99-05 — export et purge par l'API. */
class RadarPurgeExportApiIntegrationTest extends RadarIntegrationTestBase {

    @Test
    @DisplayName("l'export rend sujets, phrases avec renvois, engagements, chronologie et annuaire, échappés")
    void exportCarriesEverythingEscaped() throws Exception {
        RadarEvidence p1 = proof(aliceA, "On part sur **Okta**.", OffsetDateTime.now().minusDays(2));
        RadarEvidence p2 = proof(aliceA, "Pilote en octobre.", OffsetDateTime.now().minusDays(1));
        RadarSubject mfa = registry.createSubject(aliceA, "MFA", RadarSubjectState.ADVANCING, ids(p1, p2));
        registry.replaceSummary(aliceA, mfa.getId(), List.of(new SummarySentence("Okta retenu, pilote en octobre.",
                ids(p1, p2))));
        RadarPerson paul = registry.upsertPerson(aliceA, "paul@client.fr", "Paul", "RSSI");
        registry.assignRole(aliceA, mfa.getId(), paul.getId(), RadarRole.DECIDES, ids(p1));
        registry.recordCommitment(aliceA, new CommitmentInput(mfa.getId(), RadarCommitmentDirection.OTHER_TO_ME,
                "Valider le périmètre", paul.getId(), null, null, LocalDate.of(2026, 9, 20), true,
                RadarCertainty.PROBABLE, null, ids(p2)));
        RadarSubject ldap = registry.createSubject(aliceA, "LDAP", null, ids(proof(aliceA, "LDAP clos")));
        ldap.setState(RadarSubjectState.CLOSED);
        subjects.save(ldap);
        registry.createSubject(aliceB, "Secret CAGIP", null, ids(proof(aliceB, "ailleurs")));

        String markdown = mockMvc.perform(get(url(aliceA, "/export")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", Matchers.startsWith("text/markdown")))
                .andExpect(header().string("Content-Disposition",
                        Matchers.matchesPattern("attachment; filename=\"radar-edenred-\\d{4}-\\d{2}-\\d{2}\\.md\"")))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(markdown)
                .contains("# Radar — EDENRED")
                .contains("### MFA", "### LDAP")
                .contains("- Okta retenu, pilote en octobre. [P1][P2]")
                .contains("- Paul — décide [P1]")
                .contains("[ouvert · probable] J'attends de Paul : Valider le périmètre — échéance 2026-09-20 (déduite) [P2]")
                .contains("« On part sur \\*\\*Okta\\*\\*. »")
                .contains("[lien](https://teams.microsoft.com/l/message/")
                .contains("## Annuaire (1)", "- Paul — RSSI — MFA (décide)")
                .doesNotContain("CAGIP");
    }

    @Test
    @DisplayName("purge : 400 sans confirmation, 409 mission en cours, 200 mission close, trace")
    void purgeGuards() throws Exception {
        registry.createSubject(aliceA, "MFA", null, ids(proof(aliceA, "MFA")));

        mockMvc.perform(post(url(aliceA, "/purge")).contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"USER_REQUEST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("radar_invalid"));
        mockMvc.perform(post(url(aliceA, "/purge")).contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"HOST_DELETED\",\"confirm\":true}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(url(aliceA, "/purge")).contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"MISSION_CLOSED\",\"confirm\":true}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("radar_state_conflict"));
        assertThat(subjects.findAll()).hasSize(1);

        RadarSubject survivor = registry.createSubject(aliceB, "CAGIP", null, ids(proof(aliceB, "B")));
        RunnerHost host = hostRepository.findById(aliceA.hostId()).orElseThrow();
        host.setMissionStatus(HostMissionStatus.CLOSED);
        hostRepository.save(host);

        mockMvc.perform(post(url(aliceA, "/purge")).contextPath("/api").header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"MISSION_CLOSED\",\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reason").value("MISSION_CLOSED"))
                .andExpect(jsonPath("$.subjectsCount").value(1));

        assertThat(subjects.findAll()).extracting(RadarSubject::getId).containsExactly(survivor.getId());
        mockMvc.perform(get(url(aliceA, "/purges")).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$", Matchers.hasSize(1)));
    }

    @Test
    @DisplayName("SANS OPTION : export et purge restent possibles — le reste du Radar est refusé")
    void exportAndPurgeDoNotNeedTheOption() throws Exception {
        User carol = seedUser("carol-radar@example.com", UserRole.USER);
        RadarScope carolScope = new RadarScope(carol.getId(), seedHost(carol.getId(), "Poste de Carol"));
        registry.createSubject(carolScope, "Ancien sujet", null, ids(proof(carolScope, "vieux")));
        String token = jwtService.generateToken(carol);

        mockMvc.perform(get(url(carolScope, "/subjects")).contextPath("/api").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(url(carolScope, "/export")).contextPath("/api").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mockMvc.perform(post(url(carolScope, "/purge")).contextPath("/api").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"USER_REQUEST\",\"confirm\":true}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("ISOLATION : Bob n'exporte ni ne purge le poste d'Alice")
    void anotherUserCannotExportOrPurge() throws Exception {
        registry.createSubject(aliceA, "MFA", null, ids(proof(aliceA, "MFA")));

        mockMvc.perform(get(url(aliceA, "/export")).contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(url(aliceA, "/purge")).contextPath("/api").header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"USER_REQUEST\",\"confirm\":true}"))
                .andExpect(status().isNotFound());
        assertThat(subjects.findAll()).hasSize(1);
        mockMvc.perform(get(url(aliceA, "/export")).contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }
}
