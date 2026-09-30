package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.dto.AgentAnswerRequest;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.runner.relay.RelayTurnSource;

/**
 * <b>L'endpoint de réponse à une question structurée</b> (F-164 / SF-164-01) : il vérifie l'accès au
 * terminal, délègue au service (qui porte l'isolation) et lui propage l'exception d'isolation.
 */
class AtelierChatControllerAnswerTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID PROJET = UUID.randomUUID();

    private AtelierChatService chatService;
    private AtelierThreadService threadService;
    private CurrentUser currentUser;
    private AtelierAccessService access;
    private LiveTurnRegistry liveTurns;

    @BeforeEach
    void setUp() {
        chatService = Mockito.mock(AtelierChatService.class);
        threadService = Mockito.mock(AtelierThreadService.class);
        currentUser = Mockito.mock(CurrentUser.class);
        access = Mockito.mock(AtelierAccessService.class);
        liveTurns = new LiveTurnRegistry(new ObjectMapper());
        when(currentUser.requireId()).thenReturn(ALICE);
    }

    private AtelierChatController controller() {
        return new AtelierChatController(chatService, threadService, currentUser, access,
                Runnable::run, Runnable::run, liveTurns, RelayTurnSource.disabled(),
                new fr.claudegateway.quota.TurnCostView(
                        Mockito.mock(fr.claudegateway.admin.AdminService.class),
                        new fr.claudegateway.quota.ProviderPricingProperties(
                                null, null, null, null, null, null)),
                        org.mockito.Mockito.mock(fr.claudegateway.atelier.AtelierThreadCostService.class),
                        org.mockito.Mockito.mock(fr.claudegateway.atelier.AtelierThreadContextService.class),
                        org.mockito.Mockito.mock(fr.claudegateway.atelier.AtelierRecallService.class));
    }

    private AgentAnswerRequest request() {
        return new AgentAnswerRequest("call-1",
                List.of(new AgentAnswerRequest.Answer("Périmètre", List.of("Minimal"), null)));
    }

    @Test
    void theEndpointChecksAccessAndDelegatesToTheService() {
        controller().answer(PROJET, request());

        verify(access).requireTerminalAccess(PROJET);
        verify(chatService).answerQuestion(eq(ALICE), eq(PROJET), eq("call-1"), any());
    }

    @Test
    void theEndpointPropagatesTheIsolationFailureFromTheService() {
        doThrow(new WorkspaceNotFoundException("projet d'autrui")).when(chatService)
                .answerQuestion(any(), any(), any(), any());

        assertThatThrownBy(() -> controller().answer(PROJET, request()))
                .isInstanceOf(WorkspaceNotFoundException.class);
    }

    @Test
    void theSubmittedAnswersAreMappedToDomainEntries() {
        // Le contrat HTTP se traduit en entrées de domaine : header, options cochées, réponse libre.
        List<AtelierAnswerEntry> entries = new AgentAnswerRequest("c",
                List.of(new AgentAnswerRequest.Answer("H", List.of("A", "B"), "libre"))).toEntries();

        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).header()).isEqualTo("H");
        assertThat(entries.get(0).selected()).containsExactly("A", "B");
        assertThat(entries.get(0).other()).isEqualTo("libre");
    }
}
