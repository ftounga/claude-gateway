package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.runner.RunnerLiveness;
import fr.claudegateway.runner.door.RunnerDoor;
import fr.claudegateway.runner.door.RunnerDoorVerdict;
import fr.claudegateway.runner.door.RunnerNotReadyException;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * <b>La porte d'entrée, branchée</b> (F-161 / SF-161-01).
 *
 * <p>Ce que ce test tient, et qui est toute la valeur de la feature : quand le poste ne portera pas
 * le tour, <b>le fournisseur n'est jamais appelé</b>. Sur la session mesurée du 25/09, 8 tours
 * « Non concluant » ont coûté 11 % de la facture pour découvrir la panne <i>après</i> avoir payé.</p>
 */
class AtelierChatServiceRunnerDoorTest {

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final AtelierMessageRepository messageRepository = mock(AtelierMessageRepository.class);
    private final fr.claudegateway.byok.ByokKeyService byokKeyService =
            mock(fr.claudegateway.byok.ByokKeyService.class);
    private final fr.claudegateway.quota.QuotaService quotaService =
            mock(fr.claudegateway.quota.QuotaService.class);
    private final fr.claudegateway.runner.exec.RunnerToolGateway runnerToolGateway =
            mock(fr.claudegateway.runner.exec.RunnerToolGateway.class);
    private final RunnerLiveness liveness = mock(RunnerLiveness.class);
    private final RunnerHostService runnerHostService = mock(RunnerHostService.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        service = new AtelierChatService(workspaceService, messageRepository,
                (AiAgentProvider) agentProvider, byokKeyService, quotaService, null,
                runnerToolGateway, null, null, null,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                        null, null, true));
        service.setRunnerDoor(new RunnerDoor(liveness), runnerHostService);

        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(runnerWorkspace());
        when(runnerHostService.hostName(hostId)).thenReturn("CAGIP");
    }

    private Workspace runnerWorkspace() {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setHostId(hostId);
        workspace.setProjectPath("projet");
        workspace.setExecutionTarget(WorkspaceExecutionTarget.RUNNER);
        return workspace;
    }

    /**
     * La porte lit le battement <b>une fois</b> et en tire décision et ancienneté : on double donc
     * la lecture, pas le verdict (F-161 / SF-161-01).
     */
    private void alive(boolean value) {
        java.time.OffsetDateTime beat = value
                ? java.time.OffsetDateTime.now().minusSeconds(10)
                : java.time.OffsetDateTime.now().minusMinutes(12);
        when(liveness.lastSeenAt(userId, hostId)).thenReturn(beat);
        when(liveness.isFresh(beat)).thenReturn(value);
    }

    @Test
    @DisplayName("poste HORS LIGNE : refus, et LE FOURNISSEUR N'EST JAMAIS APPELÉ — zéro jeton")
    void offlineSpendsNothing() {
        alive(false);

        assertThatThrownBy(() -> service.chat(userId, workspaceId, "déploie la MR"))
                .isInstanceOf(RunnerNotReadyException.class)
                .hasMessageContaining("CAGIP")
                .hasMessageContaining("rien n'a été dépensé");

        assertThat(agentProvider.messageSnapshots)
                .as("AUCUN appel fournisseur : c'est toute la valeur de la porte")
                .isEmpty();
        verify(messageRepository, never()).save(any());
    }

    @Test
    @DisplayName("runner SANS bash : refus nommé, et toujours aucun appel fournisseur")
    void missingBashSpendsNothing() {
        alive(true);
        when(runnerHostService.declaredCapabilities(hostId)).thenReturn(Set.of("files", "teams"));

        assertThatThrownBy(() -> service.chat(userId, workspaceId, "lance les tests"))
                .isInstanceOf(RunnerNotReadyException.class)
                .hasMessageContaining("--no-bash");

        assertThat(agentProvider.messageSnapshots).isEmpty();
    }

    @Test
    @DisplayName("« demander quand même » passe outre, et ne vaut QUE pour l'appel qui le porte")
    void forceAppliesToThisTurnOnly() {
        alive(false);

        // Le drapeau est un ARGUMENT, pas un état posé quelque part : c'est ce qui lui permet de
        // traverser le pool SSE, où une variable de thread serait invisible (F-161 / SF-161-01).
        // Le tour passe la porte ; il échouera plus loin, faute de montage complet — ce qui importe
        // ici est qu'il ne soit PAS refusé par la porte.
        assertThatThrownBy(() -> service.chat(userId, workspaceId, "vas-y",
                fr.claudegateway.agent.AgentTurnMode.ACT, true))
                .isNotInstanceOf(RunnerNotReadyException.class);

        // L'appel SUIVANT, qui ne le porte pas, retrouve la porte fermée.
        assertThatThrownBy(() -> service.chat(userId, workspaceId, "et encore"))
                .isInstanceOf(RunnerNotReadyException.class);
    }

    @Test
    @DisplayName("ISOLATION — requireOwned passe AVANT la porte : un projet d'autrui rend 404")
    void ownershipComesFirst() {
        UUID intruder = UUID.randomUUID();
        when(workspaceService.requireOwned(intruder, workspaceId))
                .thenThrow(new WorkspaceNotFoundException("Workspace introuvable"));

        assertThatThrownBy(() -> service.chat(intruder, workspaceId, "voir"))
                .isInstanceOf(WorkspaceNotFoundException.class);

        verify(liveness, never()).lastSeenAt(any(), any());
    }

    @Test
    @DisplayName("projet HÉBERGÉ : aucune porte, la vivacité n'est pas même lue")
    void hostedProjectsHaveNoDoor() {
        Workspace hosted = runnerWorkspace();
        hosted.setHostId(null);
        hosted.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(hosted);

        assertThatThrownBy(() -> service.chat(userId, workspaceId, "bonjour"))
                .isNotInstanceOf(RunnerNotReadyException.class);

        verify(liveness, never()).lastSeenAt(any(), any());
    }

    @Test
    @DisplayName("sans porte branchée, le comportement est EXACTEMENT celui d'avant")
    void withoutTheDoorNothingChanges() {
        AtelierChatService bare = new AtelierChatService(workspaceService, messageRepository,
                (AiAgentProvider) agentProvider, byokKeyService, quotaService, null,
                runnerToolGateway, null, null, null,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                        null, null, true));

        assertThatThrownBy(() -> bare.chat(userId, workspaceId, "bonjour"))
                .isNotInstanceOf(RunnerNotReadyException.class);
        verify(liveness, never()).lastSeenAt(any(), any());
    }

    @Test
    @DisplayName("le verdict porte un code stable, que l'écran peut reconnaître")
    void theVerdictCarriesAStableCode() {
        assertThat(RunnerDoorVerdict.OFFLINE).isEqualTo("runner_offline");
        assertThat(RunnerDoorVerdict.MISSING_CAPABILITY).isEqualTo("runner_missing_capability");
    }
}
