package fr.claudegateway.atelier.nextprompt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ModelCatalog;
import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.ProviderCostCalculator;
import fr.claudegateway.quota.TurnExtras;
import fr.claudegateway.quota.TurnTokens;
import fr.claudegateway.quota.UsageLedgerService;

/** F-144 / SF-144-02 — la suite prédite : bornes, nettoyage, mémorisation, coupe-circuit, isolation. */
class NextPromptServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private WorkspaceService workspaceService;
    private AtelierMessageRepository repository;
    private AIProvider aiProvider;
    private ModelCatalog modelCatalog;
    private ByokKeyService byok;
    private UsageLedgerService ledger;
    private ProviderCostCalculator costs;
    private final List<ChatCompletionRequest> requests = new ArrayList<>();
    private String answer = "Lance les tests du module paiement.";

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        repository = mock(AtelierMessageRepository.class);
        aiProvider = mock(AIProvider.class);
        modelCatalog = mock(ModelCatalog.class);
        byok = mock(ByokKeyService.class);
        ledger = mock(UsageLedgerService.class);
        costs = mock(ProviderCostCalculator.class);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(
                Workspace.builder().id(workspaceId).userId(userId).hostId(hostId).name("paiement-api").build());
        when(modelCatalog.fastModel()).thenReturn("claude-haiku-4-5");
        when(byok.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        when(aiProvider.complete(any())).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return new ChatCompletionResult(answer, "claude-haiku-4-5", 900, 20, 0, 0);
        });
    }

    private NextPromptService service(boolean enabled) {
        return new NextPromptService(workspaceService, repository, aiProvider, modelCatalog, byok, ledger, costs,
                new NextPromptProperties(enabled));
    }

    private AtelierMessage message(String role, String content, String terminalJson) {
        return AtelierMessage.builder().id(UUID.randomUUID()).workspaceId(workspaceId).userId(userId).role(role)
                .content(content).terminalJson(terminalJson).build();
    }

    private void thread(AtelierMessage... newestFirst) {
        when(repository.findTop6ByWorkspaceIdAndUserIdOrderByCreatedAtDesc(workspaceId, userId))
                .thenReturn(List.of(newestFirst));
    }

    @Test
    @DisplayName("nominal : modèle rapide, consigne fixe, 150 jetons, matière du dernier échange, journal d'usage")
    void nominal() {
        AtelierMessage reply = message("ASSISTANT", "J'ai corrigé le calcul de TVA.",
                "{\"interrupted\":false,\"budgetReached\":false,\"plan\":[{\"title\":\"Corriger\",\"status\":\"done\"},"
                        + "{\"title\":\"Tester\",\"status\":\"pending\"}]}");
        thread(reply, message("USER", "Corrige la TVA", null), message("ASSISTANT", "ancienne", null));

        NextPromptResponse response = service(true).predict(userId, workspaceId);

        assertThat(response.suggestion()).isEqualTo("Lance les tests du module paiement.");
        assertThat(response.messageId()).isEqualTo(reply.getId());
        assertThat(requests).hasSize(1);
        ChatCompletionRequest request = requests.get(0);
        assertThat(request.model()).isEqualTo("claude-haiku-4-5");
        assertThat(request.system()).isEqualTo(NextPromptService.CONSIGNE);
        assertThat(request.maxTokens()).isEqualTo(NextPromptService.MAX_TOKENS);
        assertThat(request.apiKey()).isNull();
        String material = request.messages().get(0).content();
        assertThat(material).contains("paiement-api", "Corrige la TVA", "J'ai corrigé le calcul de TVA.",
                "étape de plan restée ouverte : Tester").doesNotContain("ancienne");
        verify(ledger).recordTurn(eq(userId), eq(workspaceId), eq(hostId), any(TurnTokens.class),
                eq(TurnExtras.NONE), any());
    }

    @Test
    @DisplayName("clé BYOK du propriétaire si présente")
    void byokKey() {
        when(byok.resolveActiveApiKey(userId)).thenReturn(Optional.of("sk-owner"));
        thread(message("ASSISTANT", "fait", null), message("USER", "fais", null));
        service(true).predict(userId, workspaceId);
        assertThat(requests.get(0).apiKey()).isEqualTo("sk-owner");
    }

    @Test
    @DisplayName("mémorisation : deux demandes pour le même message de l'agent ⇒ un seul appel")
    void memoized() {
        thread(message("ASSISTANT", "fait", null), message("USER", "fais", null));
        NextPromptService service = service(true);
        service.predict(userId, workspaceId);
        NextPromptResponse again = service.predict(userId, workspaceId);
        assertThat(again.suggestion()).isEqualTo("Lance les tests du module paiement.");
        verify(aiProvider, times(1)).complete(any());
    }

    @Test
    @DisplayName("« AUCUNE » ⇒ null, mémorisé")
    void nothingIsMemoized() {
        answer = "AUCUNE.";
        thread(message("ASSISTANT", "fait", null), message("USER", "fais", null));
        NextPromptService service = service(true);
        assertThat(service.predict(userId, workspaceId).suggestion()).isNull();
        assertThat(service.predict(userId, workspaceId).suggestion()).isNull();
        verify(aiProvider, times(1)).complete(any());
    }

    @Test
    @DisplayName("fournisseur en échec ⇒ null, non mémorisé, aucun journal")
    void providerFailure() {
        when(aiProvider.complete(any())).thenThrow(new IllegalStateException("boom"));
        thread(message("ASSISTANT", "fait", null), message("USER", "fais", null));
        NextPromptService service = service(true);
        assertThat(service.predict(userId, workspaceId).suggestion()).isNull();
        service.predict(userId, workspaceId);
        verify(aiProvider, times(2)).complete(any());
        verifyNoInteractions(ledger);
    }

    @Test
    @DisplayName("coupe-circuit : aucun appel, aucune lecture du fil")
    void disabled() {
        NextPromptResponse response = service(false).predict(userId, workspaceId);
        assertThat(response.suggestion()).isNull();
        verifyNoInteractions(aiProvider, repository, ledger);
    }

    @Test
    @DisplayName("tour non terminé (dernier message = demande) ou fil vide ⇒ aucun appel")
    void noFinishedTurn() {
        thread(message("USER", "fais", null), message("ASSISTANT", "fait", null));
        assertThat(service(true).predict(userId, workspaceId).suggestion()).isNull();
        thread();
        assertThat(service(true).predict(userId, workspaceId).messageId()).isNull();
        verify(aiProvider, never()).complete(any());
    }

    @Test
    @DisplayName("isolation : terminal d'un autre compte ⇒ 404 avant toute lecture et tout appel")
    void foreignWorkspace() {
        UUID foreign = UUID.randomUUID();
        when(workspaceService.requireOwned(userId, foreign)).thenThrow(new WorkspaceNotFoundException("x"));
        assertThatThrownBy(() -> service(true).predict(userId, foreign)).isInstanceOf(WorkspaceNotFoundException.class);
        verifyNoInteractions(aiProvider, repository);
    }

    @Test
    @DisplayName("bornes : demande ≤ 2 000 (début), réponse ≤ 4 000 (la fin est gardée)")
    void bounds() {
        String longRequest = "D".repeat(2_500);
        String longAnswer = "A".repeat(5_000) + "FIN-DE-REPONSE";
        String material = NextPromptService.material(Workspace.builder().name("t").build(),
                message("USER", longRequest, null), message("ASSISTANT", longAnswer, null));
        assertThat(material).contains("D".repeat(2_000) + "…").doesNotContain("D".repeat(2_001));
        assertThat(material).contains("FIN-DE-REPONSE").doesNotContain("A".repeat(4_000));
    }

    @Test
    @DisplayName("état du relevé : interruption, plafond, étape active prioritaire, JSON illisible ignoré")
    void turnState() {
        assertThat(NextPromptService.stateOf("{\"interrupted\":true}").describe()).contains("interrompu");
        assertThat(NextPromptService.stateOf("{\"budgetReached\":true}").describe()).contains("plafond");
        assertThat(NextPromptService.stateOf("{\"plan\":[{\"title\":\"A\",\"status\":\"pending\"},"
                + "{\"title\":\"B\",\"status\":\"active\"}]}").openStep()).isEqualTo("B");
        assertThat(NextPromptService.stateOf("pas du json").describe()).isEqualTo("terminé normalement");
        assertThat(NextPromptService.stateOf(null).describe()).isEqualTo("terminé normalement");
    }

    @Test
    @DisplayName("nettoyage : guillemets, préfixe, première ligne ; vide, AUCUNE, > 200 ⇒ null")
    void cleaning() {
        assertThat(NextPromptService.suggestionOf("« Lance les tests. »")).isEqualTo("Lance les tests.");
        assertThat(NextPromptService.suggestionOf("\"Lance les tests.\"")).isEqualTo("Lance les tests.");
        assertThat(NextPromptService.suggestionOf("Suggestion : Lance les tests.")).isEqualTo("Lance les tests.");
        assertThat(NextPromptService.suggestionOf("\n\nLance les tests.\nParce que…")).isEqualTo("Lance les tests.");
        assertThat(NextPromptService.suggestionOf("AUCUNE")).isNull();
        assertThat(NextPromptService.suggestionOf("aucune.")).isNull();
        assertThat(NextPromptService.suggestionOf("   ")).isNull();
        assertThat(NextPromptService.suggestionOf(null)).isNull();
        assertThat(NextPromptService.suggestionOf("x".repeat(201))).isNull();
        assertThat(NextPromptService.suggestionOf("x".repeat(200))).hasSize(200);
    }
}
