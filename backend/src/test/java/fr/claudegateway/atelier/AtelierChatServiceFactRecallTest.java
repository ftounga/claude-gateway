package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.quota.UsageSnapshot;

/**
 * <b>Les faits déjà connus, joints à la question</b> (F-137 / SF-137-01).
 *
 * <p>Deux garanties s'y jouent :</p>
 *
 * <ul>
 *   <li>La consigne <b>envoyée</b> au modèle porte le rappel ; le message <b>persisté</b> reste la
 *       parole de l'utilisateur. Le fil ne doit jamais montrer à l'utilisateur un message qu'il n'a
 *       pas écrit (même patron que F-115 / SF-115-03).</li>
 *   <li>Le rappel va dans le <b>message</b>, jamais dans la consigne système : il dépend de la
 *       question, donc change à chaque tour, et invaliderait le cache du préfixe (F-134).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServiceFactRecallTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private HostKnowledgeSource knowledge;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private static final String QUESTION = "est-ce que CyberArk garde les comptes à privilèges ?";
    private static final String RECALL =
            "Ce que la carte de ce client dit déjà :\n- CyberArk garde les comptes, constaté le 2026-09-15  [acces.md]\n";

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository,
                (AiAgentProvider) agentProvider, byokKeyService, quotaService,
                // Le garde-fou Git est le vrai : sur un workspace d'archive il ne fait rien, ce qui
                // évite un stub complaisant (même choix que AtelierChatServiceTest).
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService,
                        mock(fr.claudegateway.git.GitTokenService.class),
                        mock(fr.claudegateway.git.GitHubClient.class),
                        new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                mock(fr.claudegateway.runner.exec.RunnerToolGateway.class),
                mock(fr.claudegateway.runner.channel.RunnerCallDispatcher.class),
                mock(fr.claudegateway.runner.exec.RunnerConfirmationGate.class),
                mock(fr.claudegateway.runner.audit.RunnerAuditService.class),
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                mock(fr.claudegateway.runner.host.RunnerHostService.class),
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                        null, null, true),
                fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner.none(),
                null, null, null, null, null, null, null, knowledge);

        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(quotaService.currentUsage(userId))
                .thenReturn(new UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(List.of());
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        org.mockito.Mockito.lenient().when(workspaceService.tree(any(), any())).thenReturn(List.of());
        org.mockito.Mockito.lenient().when(workspaceService.readFile(any(), any(), any()))
                .thenThrow(new InvalidFilePathException("absent"));
        agentProvider.enqueueFinal("Oui.");
    }

    private String sentConversation() {
        assertThat(agentProvider.messageSnapshots).isNotEmpty();
        return agentProvider.messageSnapshots.get(0);
    }

    private AtelierMessage savedUserMessage() {
        ArgumentCaptor<AtelierMessage> saved = ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, org.mockito.Mockito.atLeastOnce()).save(saved.capture());
        return saved.getAllValues().stream()
                .filter(message -> "USER".equals(message.getRole()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("LE CRITÈRE : ce qu'on sait déjà part AVEC la question")
    void whatWeAlreadyKnowTravelsWithTheQuestion() {
        when(knowledge.factsFor(userId, workspaceId, QUESTION)).thenReturn(RECALL);

        service.chat(userId, workspaceId, QUESTION);

        assertThat(sentConversation()).contains("CyberArk garde les comptes");
        assertThat(sentConversation()).contains(QUESTION);
    }

    @Test
    @DisplayName("le message PERSISTÉ reste la parole de l'utilisateur")
    void thestoredMessageStaysTheUsersOwnWords() {
        // Le fil ne doit jamais montrer à l'utilisateur un message qu'il n'a pas écrit.
        when(knowledge.factsFor(userId, workspaceId, QUESTION)).thenReturn(RECALL);

        service.chat(userId, workspaceId, QUESTION);

        assertThat(savedUserMessage().getContent()).isEqualTo(QUESTION);
        // Rien du rappel n'a fui : ni sa provenance, ni la date de constat qu'il porte.
        assertThat(savedUserMessage().getContent())
                .doesNotContain("[acces.md]")
                .doesNotContain("constaté le");
    }

    @Test
    @DisplayName("le rappel ne touche PAS la consigne système (le cache du préfixe reste intact)")
    void therecallNeverTouchesTheSystemPrompt() {
        when(knowledge.factsFor(any(), any(), any())).thenReturn(RECALL);
        when(knowledge.outlineFor(any(), any())).thenReturn(null);

        service.chat(userId, workspaceId, QUESTION);

        assertThat(agentProvider.lastRequest.system()).doesNotContain("[acces.md]");
        assertThat(agentProvider.lastRequest.system()).doesNotContain("constaté le 2026-09-15");
    }

    @Test
    @DisplayName("sans rien de connu, la consigne est exactement la question")
    void withoutKnowledgeThePromptIsJustTheQuestion() {
        when(knowledge.factsFor(any(), any(), any())).thenReturn(null);

        service.chat(userId, workspaceId, QUESTION);

        assertThat(sentConversation()).contains(QUESTION);
        assertThat(savedUserMessage().getContent()).isEqualTo(QUESTION);
    }

    @Test
    @DisplayName("un magasin en panne ne casse pas le tour")
    void abrokenStoreDoesNotBreakTheTurn() {
        when(knowledge.factsFor(any(), any(), any()))
                .thenThrow(new IllegalStateException("magasin indisponible"));

        assertThat(service.chat(userId, workspaceId, QUESTION).reply()).isEqualTo("Oui.");
    }

    @Test
    @DisplayName("le tour signale sa fin au magasin, pour que le suivant sache")
    void theTurnTellsTheStoreItIsDone() {
        when(knowledge.factsFor(any(), any(), any())).thenReturn(null);

        service.chat(userId, workspaceId, QUESTION);

        verify(knowledge).refreshAfterTurn(userId, workspaceId);
    }
}
