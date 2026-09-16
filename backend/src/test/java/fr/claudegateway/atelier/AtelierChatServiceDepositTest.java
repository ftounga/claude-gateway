package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.deposit.AtelierDepositedFile;
import fr.claudegateway.atelier.deposit.AtelierDepositedFileRepository;
import fr.claudegateway.atelier.deposit.DepositConsumptionService;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * Consigne du tour et fichiers déposés (F-115 / SF-115-03) : la consigne envoyée au fournisseur porte
 * les <b>chemins</b> déposés depuis le dernier tour, jamais le binaire ; les dépôts sont consommés ;
 * la parole persistée de l'utilisateur reste la sienne.
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServiceDepositTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private fr.claudegateway.runner.exec.RunnerToolGateway runnerToolGateway;
    @Mock private fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    @Mock private fr.claudegateway.runner.exec.RunnerConfirmationGate confirmationGate;
    @Mock private fr.claudegateway.runner.audit.RunnerAuditService runnerAuditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;
    @Mock private AtelierDepositedFileRepository depositedFileRepository;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true));
        service.setDepositConsumptionService(new DepositConsumptionService(depositedFileRepository));

        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        org.mockito.Mockito.lenient().when(quotaService.currentUsage(userId)).thenReturn(
                new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(List.of());
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage saved = invocation.getArgument(0);
            if (saved.getId() == null) {
                saved.setId(UUID.randomUUID());
            }
            return saved;
        });
        org.mockito.Mockito.lenient().when(workspaceService.tree(userId, workspaceId)).thenReturn(List.of());
    }

    private AtelierDepositedFile deposit(String path) {
        return AtelierDepositedFile.builder()
                .id(UUID.randomUUID()).userId(userId).workspaceId(workspaceId)
                .path(path).sizeBytes(2411L).build();
    }

    private String sentMessages() {
        return String.valueOf(agentProvider.lastRequest.messages());
    }

    @Test
    void laConsignePorteLesCheminsDeposesEtPasLeBinaire() {
        when(depositedFileRepository
                .findByUserIdAndWorkspaceIdAndConsumedAtIsNullOrderByCreatedAtAsc(userId, workspaceId))
                .thenReturn(List.of(deposit("entrees/capture.png")));
        agentProvider.enqueueFinal("fini");

        service.chat(userId, workspaceId, "décris l'image");

        String sent = sentMessages();
        assertThat(sent).contains("entrees/capture.png");
        assertThat(sent).contains("read_file");
        assertThat(sent).contains("le contenu n'est pas inclus");
        assertThat(sent).contains("décris l'image");
        // Les dépôts sont consommés (marqués), pas laissés pour un tour suivant.
        verify(depositedFileRepository).saveAll(any());
    }

    @Test
    void laParolePersisteeDeLUtilisateurNePorteQueSonTexte() {
        when(depositedFileRepository
                .findByUserIdAndWorkspaceIdAndConsumedAtIsNullOrderByCreatedAtAsc(userId, workspaceId))
                .thenReturn(List.of(deposit("entrees/capture.png")));
        agentProvider.enqueueFinal("fini");

        service.chat(userId, workspaceId, "décris l'image");

        ArgumentCaptor<AtelierMessage> saved = ArgumentCaptor.forClass(AtelierMessage.class);
        verify(messageRepository, atLeastOnce()).save(saved.capture());
        AtelierMessage userMessage = saved.getAllValues().stream()
                .filter(m -> "USER".equals(m.getRole())).findFirst().orElseThrow();
        assertThat(userMessage.getContent()).isEqualTo("décris l'image");
        assertThat(userMessage.getContent()).doesNotContain("capture.png");
    }

    @Test
    void sansDepotLaConsigneEstInchangee() {
        when(depositedFileRepository
                .findByUserIdAndWorkspaceIdAndConsumedAtIsNullOrderByCreatedAtAsc(userId, workspaceId))
                .thenReturn(List.of());
        agentProvider.enqueueFinal("fini");

        service.chat(userId, workspaceId, "bonjour");

        String sent = sentMessages();
        assertThat(sent).contains("bonjour");
        assertThat(sent).doesNotContain("Fichiers déposés dans ce terminal");
    }
}
