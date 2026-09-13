package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.quota.QuotaExceededException;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.quota.TurnTokens;

/** F-104 / SF-104-05 — relances et présentations préparées, fournisseur simulé. */
class RadarDraftApiIntegrationTest extends RadarIntegrationTestBase {

    @MockitoBean private AIProvider aiProvider;
    @MockitoBean private QuotaService quotaService;

    private final List<ChatCompletionRequest> requests = new ArrayList<>();
    private String answer;

    @BeforeEach
    void provider() {
        requests.clear();
        answer = "===BROUILLON===\nBonjour Julie, as-tu pu avancer sur le retour de l'éditeur SSO ?";
        when(aiProvider.complete(any())).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return new ChatCompletionResult(answer, "m", 500, 60, 0, 0);
        });
    }

    private RadarEvidence teamsProof(RadarScope scope, String quote, OffsetDateTime at, String link, java.util.UUID author) {
        return registry.recordEvidence(scope, new RadarRegistry.EvidenceInput(RadarEvidenceSource.TEAMS_MESSAGE,
                "msg-" + java.util.UUID.randomUUID(), at, quote, link, author));
    }

    private RadarCommitment waitingForJulie() {
        RadarPerson julie = registry.upsertPerson(aliceA, "teams:julie", "Julie Martin", null);
        RadarEvidence old = teamsProof(aliceA, "Tu me fais le retour de l'éditeur ?", OffsetDateTime.now().minusDays(6),
                "https://teams.microsoft.com/l/message/old", null);
        RadarEvidence recent = teamsProof(aliceA, "Oui je t'envoie ça jeudi", OffsetDateTime.now().minusDays(4),
                "https://teams.microsoft.com/l/message/recent", julie.getId());
        RadarSubject sso = registry.createSubject(aliceA, "SSO éditeur", RadarSubjectState.WAITING, ids(old));
        registry.createSubject(aliceA, "Migration LDAP secrète", null, ids(proof(aliceA, "LDAP")));
        return registry.recordCommitment(aliceA, new RadarRegistry.CommitmentInput(sso.getId(),
                RadarCommitmentDirection.OTHER_TO_ME, "Retour de l'éditeur SSO", julie.getId(), null, null,
                java.time.LocalDate.now().minusDays(1), false, RadarCertainty.CERTAIN, null, ids(old, recent)));
    }

    private org.springframework.test.web.servlet.ResultActions draft(RadarScope scope, java.util.UUID commitmentId)
            throws Exception {
        return mockMvc.perform(post(url(scope, "/commitments/" + commitmentId + "/draft")).contextPath("/api")
                .header("Authorization", "Bearer " + aliceToken));
    }

    @Test
    @DisplayName("relance : consigne, modèle rapide, 600 jetons, matière de l'engagement seul, lien Teams le plus récent, décompte")
    void followUp() throws Exception {
        RadarCommitment commitment = waitingForJulie();

        draft(aliceA, commitment.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("FOLLOW_UP"))
                .andExpect(jsonPath("$.text").value("Bonjour Julie, as-tu pu avancer sur le retour de l'éditeur SSO ?"))
                .andExpect(jsonPath("$.conversationUrl").value("https://teams.microsoft.com/l/message/recent"))
                .andExpect(jsonPath("$.preparedAt").exists());

        assertThat(requests).hasSize(1);
        ChatCompletionRequest request = requests.get(0);
        assertThat(request.system()).isEqualTo(RadarDraftService.CONSIGNE);
        assertThat(request.maxTokens()).isEqualTo(RadarDraftService.MAX_TOKENS);
        String material = request.messages().get(0).content();
        assertThat(material).contains("relance", "Retour de l'éditeur SSO", "PERSONNE QUI DOIT : Julie Martin",
                "Oui je t'envoie ça jeudi", "Julie Martin : « Oui").doesNotContain("LDAP");
        verify(quotaService).assertWithinQuota(alice.getId());
        verify(quotaService).recordUsage(eq(alice.getId()), any(TurnTokens.class), isNull(), isNull(), eq(aliceA.hostId()));
    }

    @Test
    @DisplayName("présentation : les deux personnes nommées ; sans preuve Teams, pas de lien")
    void introduction() throws Exception {
        RadarPerson sophie = registry.upsertPerson(aliceA, "teams:sophie", "Sophie Leroy", null);
        RadarPerson karim = registry.upsertPerson(aliceA, "teams:karim", "Karim B.", null);
        RadarEvidence note = registry.recordEvidence(aliceA, new RadarRegistry.EvidenceInput(RadarEvidenceSource.USER_NOTE,
                "note:1", OffsetDateTime.now().minusDays(1), "je dois présenter Sophie à Karim", null, null));
        RadarSubject network = registry.createSubject(aliceA, "Accès réseau", null, ids(note));
        RadarCommitment intro = registry.recordCommitment(aliceA, new RadarRegistry.CommitmentInput(network.getId(),
                RadarCommitmentDirection.INTRODUCTION, "Présenter Sophie à Karim pour les plages IP", null, sophie.getId(),
                karim.getId(), null, false, RadarCertainty.CERTAIN, null, ids(note)));
        answer = "===BROUILLON===\nSophie, je te présente Karim.";

        draft(aliceA, intro.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind").value("INTRODUCTION"))
                .andExpect(jsonPath("$.conversationUrl").doesNotExist());
        assertThat(requests.get(0).messages().get(0).content()).contains("PERSONNES À PRÉSENTER : Sophie Leroy et Karim B.");
    }

    @Test
    @DisplayName("non applicable → 409 ; quota → 402 ; ISOLATION autre poste / autre compte → 404 ; aucun appel")
    void refusalsWithoutCall() throws Exception {
        RadarSubject subject = registry.createSubject(aliceA, "MFA", null, ids(proof(aliceA, "MFA")));
        RadarCommitment mine = registry.recordCommitment(aliceA, new RadarRegistry.CommitmentInput(subject.getId(),
                RadarCommitmentDirection.ME_TO_OTHER, "Envoyer la note DSI", null, null, null, null, false,
                RadarCertainty.CERTAIN, null, ids(proof(aliceA, "je l'envoie"))));
        draft(aliceA, mine.getId()).andExpect(status().isConflict());

        RadarCommitment waiting = waitingForJulie();
        correctionService.correctCommitment(aliceA, waiting.getId(),
                new fr.claudegateway.radar.dto.RadarCorrectionRequests.CommitmentCorrectionRequest(RadarCorrectionAction.DONE, null));
        draft(aliceA, waiting.getId()).andExpect(status().isConflict());

        RadarSubject cagip = registry.createSubject(aliceB, "CAGIP", null, ids(proof(aliceB, "CAGIP")));
        RadarPerson paul = registry.upsertPerson(aliceB, "teams:paul", "Paul", null);
        RadarCommitment foreign = registry.recordCommitment(aliceB, new RadarRegistry.CommitmentInput(cagip.getId(),
                RadarCommitmentDirection.OTHER_TO_ME, "Secret CAGIP", paul.getId(), null, null, null, false,
                RadarCertainty.CERTAIN, null, ids(proof(aliceB, "secret"))));
        draft(aliceA, foreign.getId()).andExpect(status().isNotFound());
        draft(bobScope, foreign.getId()).andExpect(status().isNotFound());

        RadarCommitment open = registry.recordCommitment(aliceA, new RadarRegistry.CommitmentInput(subject.getId(),
                RadarCommitmentDirection.OTHER_TO_ME, "Retour", registry.upsertPerson(aliceA, "teams:x", "X", null).getId(),
                null, null, null, false, RadarCertainty.CERTAIN, null, ids(proof(aliceA, "retour"))));
        doThrow(new QuotaExceededException("quota")).when(quotaService).assertWithinQuota(alice.getId());
        draft(aliceA, open.getId()).andExpect(status().isPaymentRequired());

        verify(aiProvider, never()).complete(any());
    }

    @Test
    @DisplayName("sortie illisible → 502, consommation décomptée")
    void unreadable() throws Exception {
        RadarCommitment commitment = waitingForJulie();
        answer = "Bonjour Julie, sans marqueur.";

        draft(aliceA, commitment.getId()).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("radar_answer_unreadable"));
        verify(quotaService).recordUsage(eq(alice.getId()), any(TurnTokens.class), isNull(), isNull(), eq(aliceA.hostId()));
    }
}
