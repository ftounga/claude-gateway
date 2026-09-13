package fr.claudegateway.radar;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.radar.RadarRegistry.CommitmentInput;
import fr.claudegateway.radar.RadarRegistry.SummarySentence;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRole;

/** F-99 / SF-99-01 — la lecture REST du Radar, de bout en bout. */
class RadarReadApiIntegrationTest extends RadarIntegrationTestBase {

    @Test
    @DisplayName("la page d'un sujet rend chaque phrase du résumé avec ses renvois")
    void subjectPageCarriesEvidenceForEverySentence() throws Exception {
        RadarEvidence p1 = proof(aliceA, "On part sur Okta.", OffsetDateTime.now().minusDays(2));
        RadarEvidence p2 = proof(aliceA, "Pilote début octobre.", OffsetDateTime.now().minusDays(1));
        RadarSubject mfa = registry.createSubject(aliceA, "MFA", RadarSubjectState.ADVANCING, ids(p1));
        registry.setNextStep(aliceA, mfa.getId(), "Lancer le pilote", ids(p2));
        registry.setDueDate(aliceA, mfa.getId(), LocalDate.of(2026, 10, 1), ids(p2));
        registry.replaceSummary(aliceA, mfa.getId(), List.of(
                new SummarySentence("Okta est retenu.", ids(p1)),
                new SummarySentence("Le pilote démarre début octobre.", ids(p2))));
        RadarPerson paul = registry.upsertPerson(aliceA, "paul@client.fr", "Paul", "RSSI");
        registry.assignRole(aliceA, mfa.getId(), paul.getId(), RadarRole.DECIDES, ids(p1));
        registry.recordCommitment(aliceA, new CommitmentInput(mfa.getId(), RadarCommitmentDirection.OTHER_TO_ME,
                "Valider le périmètre", paul.getId(), null, null, LocalDate.of(2026, 9, 20), false,
                RadarCertainty.CERTAIN, null, ids(p2)));

        mockMvc.perform(get(url(aliceA, "/subjects/" + mfa.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("MFA"))
                .andExpect(jsonPath("$.state").value("ADVANCING"))
                .andExpect(jsonPath("$.nextStep").value("Lancer le pilote"))
                .andExpect(jsonPath("$.dueDate").value("2026-10-01"))
                .andExpect(jsonPath("$.nextStepEvidenceIds[0]").value(p2.getId().toString()))
                .andExpect(jsonPath("$.summary", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.summary[0].text").value("Okta est retenu."))
                .andExpect(jsonPath("$.summary[0].evidenceIds[0]").value(p1.getId().toString()))
                .andExpect(jsonPath("$.summary[1].evidenceIds[0]").value(p2.getId().toString()))
                .andExpect(jsonPath("$.people[0].displayName").value("Paul"))
                .andExpect(jsonPath("$.people[0].role").value("DECIDES"))
                .andExpect(jsonPath("$.commitments[0].direction").value("OTHER_TO_ME"))
                .andExpect(jsonPath("$.commitments[0].fromPerson.displayName").value("Paul"))
                .andExpect(jsonPath("$.commitments[0].certainty").value("CERTAIN"))
                .andExpect(jsonPath("$.commitments[0].evidenceIds", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.chronology", Matchers.hasSize(2)))
                // Plus récente d'abord.
                .andExpect(jsonPath("$.chronology[0].quote").value("Pilote début octobre."))
                .andExpect(jsonPath("$.chronology[0].source").value("TEAMS_MESSAGE"));
    }

    @Test
    @DisplayName("la liste des sujets exclut les sujets clos par défaut")
    void closedSubjectsLeaveTheDefaultList() throws Exception {
        RadarSubject open = registry.createSubject(aliceA, "MFA", null, ids(proof(aliceA, "MFA")));
        RadarSubject closed = registry.createSubject(aliceA, "LDAP", null, ids(proof(aliceA, "LDAP")));
        closed.setState(RadarSubjectState.CLOSED);
        subjects.save(closed);

        mockMvc.perform(get(url(aliceA, "/subjects")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(open.getId().toString()));

        mockMvc.perform(get(url(aliceA, "/subjects")).param("includeClosed", "true").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(2)));

        mockMvc.perform(get(url(aliceA, "/subjects")).param("state", "CLOSED").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("LDAP"));
    }

    @Test
    void commitmentsPeopleEvidenceAndSyncsAreReadable() throws Exception {
        RadarEvidence p = proof(aliceA, "Je te présente Léa.");
        RadarSubject subject = registry.createSubject(aliceA, "MFA", null, ids(p));
        RadarPerson paul = registry.upsertPerson(aliceA, "paul@client.fr", "Paul", null);
        RadarPerson lea = registry.upsertPerson(aliceA, "lea@client.fr", "Léa", null);
        registry.assignRole(aliceA, subject.getId(), lea.getId(), RadarRole.EXPERT, ids(p));
        registry.recordCommitment(aliceA, new CommitmentInput(subject.getId(), RadarCommitmentDirection.INTRODUCTION,
                "Présenter Paul à Léa", null, paul.getId(), lea.getId(), null, false, RadarCertainty.PROBABLE,
                null, ids(p)));
        registry.recordCommitment(aliceA, new CommitmentInput(subject.getId(), RadarCommitmentDirection.ME_TO_OTHER,
                "Envoyer le plan", null, null, null, null, false, RadarCertainty.CERTAIN, null, ids(p)));
        registry.startSync(aliceA);

        mockMvc.perform(get(url(aliceA, "/commitments")).param("direction", "INTRODUCTION").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].toPerson.displayName").value("Paul"))
                .andExpect(jsonPath("$[0].otherPerson.displayName").value("Léa"))
                .andExpect(jsonPath("$[0].certainty").value("PROBABLE"))
                .andExpect(jsonPath("$[0].subjectName").value("MFA"));

        mockMvc.perform(get(url(aliceA, "/commitments")).param("status", "OPEN").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(2)));

        mockMvc.perform(get(url(aliceA, "/people")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(2)))
                .andExpect(jsonPath("$[0].displayName").value("Léa"))
                .andExpect(jsonPath("$[0].subjects[0].role").value("EXPERT"))
                .andExpect(jsonPath("$[1].subjects", Matchers.hasSize(0)));

        mockMvc.perform(get(url(aliceA, "/evidence/" + p.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quote").value("Je te présente Léa."));

        mockMvc.perform(get(url(aliceA, "/syncs")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("RUNNING"));
    }

    @Test
    void invalidEnumIsA400() throws Exception {
        mockMvc.perform(get(url(aliceA, "/subjects")).param("state", "FINI").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_error"));
    }

    @Test
    void anonymousIsA401() throws Exception {
        mockMvc.perform(get(url(aliceA, "/subjects")).contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("SANS LE DROIT : un utilisateur ordinaire sans option Teams reçoit 403")
    void withoutTeamsEntitlementIsA403() throws Exception {
        User carol = seedUser("carol-radar@example.com", UserRole.USER);
        RadarScope carolScope = new RadarScope(carol.getId(), seedHost(carol.getId(), "Poste de Carol"));

        mockMvc.perform(get(url(carolScope, "/subjects")).contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(carol)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("teams_forbidden"));
    }
}
