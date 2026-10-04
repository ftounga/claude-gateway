package fr.claudegateway.atelier.nextprompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** F-144 / SF-144-02 — {@code POST /api/workspaces/{id}/next-prompt}, fournisseur simulé. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NextPromptApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private AtelierMessageRepository messageRepository;
    @Autowired private JwtService jwtService;

    @MockitoBean private AIProvider aiProvider;
    @MockitoBean private QuotaService quotaService;

    private final List<ChatCompletionRequest> requests = new ArrayList<>();
    private User alice;
    private String aliceToken;
    private User bob;
    private String bobToken;

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        workspaceRepository.deleteAll();
        alice = userRepository.save(User.builder().email("np-alice-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        aliceToken = jwtService.generateToken(alice);
        bob = userRepository.save(User.builder().email("np-bob-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        bobToken = jwtService.generateToken(bob);
        requests.clear();
        when(aiProvider.complete(any())).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return new ChatCompletionResult("« Lance les tests du module paiement. »", "claude-haiku-4-5", 800, 15, 0, 0);
        });
    }

    private Workspace workspace(User owner) {
        return workspaceRepository.save(Workspace.builder().userId(owner.getId()).name("paiement-api")
                .executionTarget(WorkspaceExecutionTarget.SANDBOX).build());
    }

    private void exchange(Workspace workspace, String request, String reply) throws InterruptedException {
        messageRepository.save(AtelierMessage.builder().workspaceId(workspace.getId()).userId(workspace.getUserId())
                .role("USER").content(request).build());
        Thread.sleep(5); // created_at strictement croissant
        messageRepository.save(AtelierMessage.builder().workspaceId(workspace.getId()).userId(workspace.getUserId())
                .role("ASSISTANT").content(reply).build());
    }

    private ResultActions predict(UUID workspaceId, String token) throws Exception {
        var request = post("/api/workspaces/" + workspaceId + "/next-prompt").contextPath("/api");
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request);
    }

    @Test
    @DisplayName("nominal : la suite nettoyée, un seul appel pour le même tour, jamais le quota")
    void nominal() throws Exception {
        Workspace mine = workspace(alice);
        exchange(mine, "Corrige la TVA", "J'ai corrigé le calcul de TVA dans Facture.java.");

        predict(mine.getId(), aliceToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestion").value("Lance les tests du module paiement."))
                .andExpect(jsonPath("$.messageId").exists());
        predict(mine.getId(), aliceToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestion").value("Lance les tests du module paiement."));

        verify(aiProvider, times(1)).complete(any());
        assertThat(requests.get(0).messages().get(0).content()).contains("Corrige la TVA", "Facture.java");
        verifyNoInteractions(quotaService);
    }

    @Test
    @DisplayName("isolation : le terminal de Bob est introuvable pour Alice — 404, aucun appel")
    void isolation() throws Exception {
        Workspace bobs = workspace(bob);
        exchange(bobs, "Secret CAGIP", "réponse secrète");

        predict(bobs.getId(), aliceToken).andExpect(status().isNotFound());
        predict(UUID.randomUUID(), aliceToken).andExpect(status().isNotFound());
        verify(aiProvider, never()).complete(any());

        predict(bobs.getId(), bobToken).andExpect(status().isOk());
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).messages().get(0).content()).contains("Secret CAGIP");
    }

    @Test
    @DisplayName("sans jeton : 401 ; fil sans réponse : suggestion nulle, aucun appel")
    void unauthenticatedAndEmpty() throws Exception {
        Workspace mine = workspace(alice);
        predict(mine.getId(), null).andExpect(status().isUnauthorized());
        predict(mine.getId(), aliceToken)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.suggestion").doesNotExist());
        verify(aiProvider, never()).complete(any());
    }
}
