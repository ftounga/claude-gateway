package fr.claudegateway.radar;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-102 / SF-102-02 — les trois colonnes par l'API, poste par poste. */
class RadarBoardApiIntegrationTest extends RadarIntegrationTestBase {

    private RadarCommitment commitment(RadarScope scope, RadarSubject subject, RadarCommitmentDirection direction,
            String what, LocalDate due, RadarCertainty certainty, RadarPerson from, RadarEvidence... proofs) {
        return registry.recordCommitment(scope, new RadarRegistry.CommitmentInput(subject.getId(), direction, what,
                from == null ? null : from.getId(), null, null, due, false, certainty, null, ids(proofs)));
    }

    @Test
    @DisplayName("GET /board : colonnes rangées et enrichies, sujets avec ligne, preuves et personnes — rien d'ailleurs")
    void boardOfOneHost() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        RadarEvidence older = proof(aliceA, "On valide le périmètre ?", OffsetDateTime.now().minusDays(2));
        RadarEvidence newer = registry.recordEvidence(aliceA, new RadarRegistry.EvidenceInput(
                RadarEvidenceSource.TEAMS_MEETING, "meeting-1", OffsetDateTime.now().minusHours(3),
                "OK pour moi sur les 340.", "https://teams.microsoft.com/l/meetup/1", null));
        RadarSubject mfa = registry.createSubject(aliceA, "MFA prestataires", RadarSubjectState.ADVANCING,
                ids(older, newer));
        registry.replaceSummary(aliceA, mfa.getId(), List.of(
                new RadarRegistry.SummarySentence("Périmètre validé par Paul.", ids(newer))));
        RadarPerson paul = registry.upsertPerson(aliceA, "paul@client.fr", "Paul Martin", "DSI");
        RadarPerson sophie = registry.upsertPerson(aliceA, "sophie@client.fr", "Sophie Laurent", null);
        registry.assignRole(aliceA, mfa.getId(), paul.getId(), RadarRole.DECIDES, ids(newer));
        registry.assignRole(aliceA, mfa.getId(), sophie.getId(), RadarRole.DRIVES, ids(older));

        commitment(aliceA, mfa, RadarCommitmentDirection.ME_TO_OTHER, "Plus tard", today.plusDays(9),
                RadarCertainty.CERTAIN, null, older);
        commitment(aliceA, mfa, RadarCommitmentDirection.ME_TO_OTHER, "Note DSI", null, RadarCertainty.PROBABLE,
                null, newer);
        commitment(aliceA, mfa, RadarCommitmentDirection.ME_TO_OTHER, "Matrice des accès", today.minusDays(2),
                RadarCertainty.CERTAIN, null, older, newer);
        RadarCommitment kept = commitment(aliceA, mfa, RadarCommitmentDirection.ME_TO_OTHER, "Déjà tenu", null,
                RadarCertainty.CERTAIN, null, older);
        registry.markCommitment(aliceA, kept.getId(), RadarCommitmentStatus.KEPT, ids(newer));
        commitment(aliceA, mfa, RadarCommitmentDirection.OTHER_TO_ME, "Plages IP", today.plusDays(3),
                RadarCertainty.CERTAIN, sophie, older);

        RadarSubject waitingSubject = registry.createSubject(aliceA, "Bascule SSO", RadarSubjectState.WAITING,
                ids(proof(aliceA, "On attend l'éditeur.")));
        registry.setNextStep(aliceA, waitingSubject.getId(), "Retour de l'éditeur",
                ids(proof(aliceA, "Retour promis début septembre.")));

        // Ailleurs : l'autre poste d'Alice et celui de Bob.
        RadarEvidence other = proof(aliceB, "CAGIP");
        commitment(aliceB, registry.createSubject(aliceB, "Sujet CAGIP", null, ids(other)),
                RadarCommitmentDirection.ME_TO_OTHER, "Engagement CAGIP", null, RadarCertainty.CERTAIN, null, other);
        registry.createSubject(bobScope, "Sujet de Bob", null, ids(proof(bobScope, "Bob")));

        mockMvc.perform(get(url(aliceA, "/board")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toDo", hasSize(3)))
                .andExpect(jsonPath("$.toDo[0].commitment.description").value("Matrice des accès"))
                .andExpect(jsonPath("$.toDo[0].due").value(true))
                .andExpect(jsonPath("$.toDo[0].overdueDays").value(2))
                .andExpect(jsonPath("$.toDo[0].source").value("TEAMS_MEETING"))
                .andExpect(jsonPath("$.toDo[0].deepLink").value("https://teams.microsoft.com/l/meetup/1"))
                .andExpect(jsonPath("$.toDo[1].commitment.description").value("Note DSI"))
                .andExpect(jsonPath("$.toDo[1].question").value(true))
                .andExpect(jsonPath("$.toDo[2].commitment.description").value("Plus tard"))
                .andExpect(jsonPath("$.waiting", hasSize(1)))
                .andExpect(jsonPath("$.waiting[0].commitment.fromPerson.displayName").value("Sophie Laurent"))
                .andExpect(jsonPath("$.waiting[0].due").value(false))
                .andExpect(jsonPath("$.subjects", hasSize(2)))
                .andExpect(jsonPath("$.subjects[?(@.subject.name == 'MFA prestataires')].line")
                        .value("Périmètre validé par Paul."))
                .andExpect(jsonPath("$.subjects[?(@.subject.name == 'MFA prestataires')].sources").value(2))
                .andExpect(jsonPath("$.subjects[?(@.subject.name == 'MFA prestataires')].people[0]").value("Paul Martin"))
                .andExpect(jsonPath("$.subjects[?(@.subject.name == 'MFA prestataires')].people[1]").value("Sophie Laurent"))
                .andExpect(jsonPath("$.subjects[?(@.subject.name == 'Bascule SSO')].line").value("Retour de l'éditeur"));

        mockMvc.perform(get(url(aliceB, "/board")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toDo", hasSize(1)))
                .andExpect(jsonPath("$.toDo[0].commitment.description").value("Engagement CAGIP"))
                .andExpect(jsonPath("$.waiting", hasSize(0)))
                .andExpect(jsonPath("$.subjects", hasSize(1)));
    }

    @Test
    @DisplayName("isolation : le poste d'autrui est introuvable")
    void isolation() throws Exception {
        mockMvc.perform(get(url(aliceA, "/board")).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }
}
