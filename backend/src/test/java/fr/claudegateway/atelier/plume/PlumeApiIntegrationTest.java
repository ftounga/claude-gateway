package fr.claudegateway.atelier.plume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.AIProviderException;
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

/** F-193 / SF-193-01 — {@code POST /api/workspaces/{id}/enrich-prompt}, fournisseur simulé. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlumeApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private AtelierMessageRepository messageRepository;
    @Autowired private JwtService jwtService;
    @Autowired private fr.claudegateway.videos.VideoSeriesRepository seriesRepository;

    @MockitoBean private AIProvider aiProvider;
    @MockitoBean private QuotaService quotaService;
    @MockitoBean private PlumeProperties plumeProperties;

    private final ObjectMapper json = new ObjectMapper();
    private final List<ChatCompletionRequest> requests = new ArrayList<>();
    private User alice;
    private String aliceToken;
    private User bob;
    private String bobToken;

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        workspaceRepository.deleteAll();
        alice = userRepository.save(User.builder().email("plume-alice-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        aliceToken = jwtService.generateToken(alice);
        bob = userRepository.save(User.builder().email("plume-bob-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        bobToken = jwtService.generateToken(bob);
        requests.clear();
        when(plumeProperties.isEnabled()).thenReturn(true);
        when(aiProvider.complete(any())).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return new ChatCompletionResult("## Objectif\nPaginer l'export.\n\n## Points à confirmer\n- Taille 50",
                    "claude-opus-5", 900, 80, 0, 0);
        });
    }

    private Workspace workspace(User owner) {
        return workspaceRepository.save(Workspace.builder().userId(owner.getId()).name("export-api")
                .executionTarget(WorkspaceExecutionTarget.SANDBOX).build());
    }

    private void exchange(Workspace workspace, String request, String reply) throws InterruptedException {
        messageRepository.save(AtelierMessage.builder().workspaceId(workspace.getId()).userId(workspace.getUserId())
                .role("USER").content(request).build());
        Thread.sleep(5); // created_at strictement croissant
        messageRepository.save(AtelierMessage.builder().workspaceId(workspace.getId()).userId(workspace.getUserId())
                .role("ASSISTANT").content(reply).build());
    }

    private ResultActions enrich(UUID workspaceId, String token, String text) throws Exception {
        var request = post("/api/workspaces/" + workspaceId + "/enrich-prompt").contextPath("/api")
                .contentType(MediaType.APPLICATION_JSON)
                .content(text == null ? "{}" : json.writeValueAsString(java.util.Map.of("text", text)));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request);
    }

    @Test
    @DisplayName("nominal : demande composée, à confirmer, sources ; fil du terminal dans la matière ; jamais le quota")
    void nominal() throws Exception {
        Workspace mine = workspace(alice);
        exchange(mine, "Corrige le curseur", "Curseur corrigé dans ExportService.java");

        enrich(mine.getId(), aliceToken, "ajoute la pagination")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enrichedPrompt").value(
                        "## Objectif\nPaginer l'export.\n\n## Points à confirmer\n- Taille 50"))
                .andExpect(jsonPath("$.toConfirm[0]").value("Taille 50"))
                .andExpect(jsonPath("$.sources[0]").value("Fil récent du sujet (2 messages)"));

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).messages().get(0).content())
                .contains("ajoute la pagination", "Corrige le curseur", "ExportService.java");
        verifyNoInteractions(quotaService);
    }

    @Test
    @DisplayName("SF-215-05 : demande de vidéo — cahier de réalisation, mode video, séries du compte seulement")
    void videoMode() throws Exception {
        Workspace mine = workspace(alice);
        Workspace bobs = workspace(bob);
        seriesRepository.save(fr.claudegateway.videos.VideoSeries.builder().id(UUID.randomUUID())
                .userId(alice.getId()).workspaceId(mine.getId()).title("Cours export").voice("nova").build());
        seriesRepository.save(fr.claudegateway.videos.VideoSeries.builder().id(UUID.randomUUID())
                .userId(bob.getId()).workspaceId(bobs.getId()).title("Série secrète de Bob").build());

        enrich(mine.getId(), aliceToken, "fais une vidéo qui explique l'export")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("video"))
                .andExpect(jsonPath("$.sources").value(org.hamcrest.Matchers.hasItem("Séries vidéo du sujet")));

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).system()).isEqualTo(PlumeService.CONSIGNE_VIDEO);
        assertThat(requests.get(0).messages().get(0).content())
                .contains("Cours export — 0 épisode, voix fixée : nova").doesNotContain("Série secrète de Bob");

        enrich(mine.getId(), aliceToken, "ajoute la pagination").andExpect(jsonPath("$.mode").value("prompt"));
        assertThat(requests.get(1).system()).isEqualTo(PlumeService.CONSIGNE);
    }

    @Test
    @DisplayName("isolation : le terminal de Bob est introuvable pour Alice — 404, aucun appel")
    void isolation() throws Exception {
        Workspace bobs = workspace(bob);
        exchange(bobs, "Secret CAGIP", "réponse secrète");

        enrich(bobs.getId(), aliceToken, "résume").andExpect(status().isNotFound());
        enrich(UUID.randomUUID(), aliceToken, "résume").andExpect(status().isNotFound());
        verify(aiProvider, never()).complete(any());

        enrich(bobs.getId(), bobToken, "résume").andExpect(status().isOk());
        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).messages().get(0).content()).contains("Secret CAGIP");
    }

    @Test
    @DisplayName("saisie vide, blanche ou trop longue : 400 ; sans jeton : 401 ; aucun appel")
    void invalid() throws Exception {
        Workspace mine = workspace(alice);
        enrich(mine.getId(), aliceToken, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        enrich(mine.getId(), aliceToken, "   ").andExpect(status().isBadRequest());
        enrich(mine.getId(), aliceToken, "x".repeat(4_001)).andExpect(status().isBadRequest());
        enrich(mine.getId(), null, "x").andExpect(status().isUnauthorized());
        verify(aiProvider, never()).complete(any());
    }

    @Test
    @DisplayName("coupe-circuit fermé : 503, aucun appel ; fournisseur en échec : 502")
    void disabledAndFailure() throws Exception {
        Workspace mine = workspace(alice);
        when(plumeProperties.isEnabled()).thenReturn(false);
        enrich(mine.getId(), aliceToken, "x").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("Plume est désactivé sur cette installation."));
        verify(aiProvider, never()).complete(any());

        when(plumeProperties.isEnabled()).thenReturn(true);
        when(aiProvider.complete(any())).thenThrow(new AIProviderException("amont"));
        enrich(mine.getId(), aliceToken, "x").andExpect(status().isBadGateway());
    }

    @Test
    @DisplayName("F-202 : tronqué deux fois — 502 au message dédié, deux appels, jamais un fragment")
    void truncatedTwice() throws Exception {
        Workspace mine = workspace(alice);
        when(aiProvider.complete(any())).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return new ChatCompletionResult("## Objectif\nPagi", "claude-opus-5", 900, 6_000, 0, 0, true);
        });
        requests.clear(); // le ré-stubbage a invoqué l'ancienne réponse avec un argument nul
        enrich(mine.getId(), aliceToken, "x").andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("longueur prévue")));
        assertThat(requests).extracting(ChatCompletionRequest::maxTokens)
                .containsExactly(PlumeService.MAX_TOKENS, PlumeService.RETRY_MAX_TOKENS);
    }
}
