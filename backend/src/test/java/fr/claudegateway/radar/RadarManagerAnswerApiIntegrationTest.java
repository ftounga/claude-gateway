package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.quota.TurnTokens;
import fr.claudegateway.radar.RadarRegistry.SummarySentence;
import fr.claudegateway.runner.host.ClientSpace;

/** F-103 / SF-103-03 — la réponse au manager, de bout en bout, fournisseur simulé. */
class RadarManagerAnswerApiIntegrationTest extends RadarIntegrationTestBase {

    @MockitoBean private AIProvider aiProvider;
    @MockitoBean private QuotaService quotaService;

    private final List<ChatCompletionRequest> requests = new ArrayList<>();
    private String answer;

    @BeforeEach
    void provider() {
        requests.clear();
        answer = "===REPONSE===\nLe périmètre MFA est validé ; le pilote suit.";
        when(aiProvider.complete(any())).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return new ChatCompletionResult(answer, "m", 800, 90, 0, 0);
        });
    }

    private RadarSubject seedMfa() {
        RadarEvidence p = proof(aliceA, "Périmètre validé.");
        RadarSubject mfa = registry.createSubject(aliceA, "MFA prestataires", RadarSubjectState.ADVANCING, ids(p));
        registry.replaceSummary(aliceA, mfa.getId(), List.of(new SummarySentence("Le périmètre est validé.", ids(p))));
        // Un autre sujet du même poste, et un sujet d'un autre poste : jamais dans la matière.
        registry.createSubject(aliceA, "Migration LDAP secrète", null, ids(proof(aliceA, "LDAP")));
        registry.createSubject(aliceB, "Sujet CAGIP confidentiel", null, ids(proof(aliceB, "CAGIP")));
        return mfa;
    }

    @Test
    @DisplayName("nominal : la réponse, une matière limitée au sujet, la consommation décomptée sur le poste")
    void nominal() throws Exception {
        RadarSubject mfa = seedMfa();

        mockMvc.perform(post(url(aliceA, "/subjects/" + mfa.getId() + "/manager-answer")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Le périmètre MFA est validé ; le pilote suit."))
                .andExpect(jsonPath("$.coverageIncomplete").value(false))
                .andExpect(jsonPath("$.preparedAt").exists());

        assertThat(requests).hasSize(1);
        String material = requests.get(0).messages().get(0).content();
        assertThat(material).contains("MFA prestataires", "Le périmètre est validé.")
                .doesNotContain("LDAP", "CAGIP");
        verify(quotaService).assertWithinQuota(alice.getId());
        verify(quotaService).recordUsage(eq(alice.getId()), any(TurnTokens.class), isNull(), isNull(), eq(aliceA.hostId()));
    }

    @Test
    @DisplayName("ISOLATION : autre poste, autre compte → 404 ; hors Vigie → 409 ; aucun appel au fournisseur")
    void isolation() throws Exception {
        RadarSubject mfa = seedMfa();

        mockMvc.perform(post(url(aliceB, "/subjects/" + mfa.getId() + "/manager-answer")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(url(aliceA, "/subjects/" + mfa.getId() + "/manager-answer")).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        hostSpaces.findAll().stream()
                .filter(s -> s.getHostId().equals(aliceA.hostId()) && s.getSpace() == ClientSpace.VIGIE)
                .forEach(hostSpaces::delete);
        mockMvc.perform(post(url(aliceA, "/subjects/" + mfa.getId() + "/manager-answer")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict());

        verify(aiProvider, never()).complete(any());
        verify(quotaService, never()).recordUsage(any(), any(TurnTokens.class), any(), any(), any());
    }

    @Test
    @DisplayName("sortie illisible → 502 radar_answer_unreadable")
    void unreadable() throws Exception {
        RadarSubject mfa = seedMfa();
        answer = "Pas de marqueur.";

        mockMvc.perform(post(url(aliceA, "/subjects/" + mfa.getId() + "/manager-answer")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("radar_answer_unreadable"));
    }
}
