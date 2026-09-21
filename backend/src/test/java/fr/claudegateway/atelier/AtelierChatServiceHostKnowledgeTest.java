package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * <b>Ce que la boucle sait déjà du client</b>, dans la consigne système (F-136 / SF-136-02).
 *
 * <p>Deux garanties s'y jouent, et aucune n'est négociable :</p>
 *
 * <ul>
 *   <li><b>L'isolation</b> — la carte d'un client ne doit jamais entrer dans le tour d'un autre,
 *       <b>y compris entre deux postes du même utilisateur</b>. C'est la question que le PO a posée
 *       en premier, et elle se prouve, elle ne se promet pas.</li>
 *   <li><b>Le cache</b> — le bloc vit dans le préfixe stable : deux tours sans changement de carte
 *       doivent produire la même consigne à l'octet, sans quoi F-134 est annulée.</li>
 * </ul>
 */
class AtelierChatServiceHostKnowledgeTest {

    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final AtelierMessageRepository messageRepository = mock(AtelierMessageRepository.class);
    private final HostKnowledgeSource knowledge = mock(HostKnowledgeSource.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceA = UUID.randomUUID();
    private final UUID workspaceB = UUID.randomUUID();

    private AtelierChatService service;

    @BeforeEach
    void setUp() {
        service = new AtelierChatService(workspaceService, messageRepository,
                mock(AiAgentProvider.class), mock(ByokKeyService.class), mock(QuotaService.class),
                null, mock(RunnerToolGateway.class), mock(RunnerCallDispatcher.class),
                mock(RunnerConfirmationGate.class), mock(RunnerAuditService.class),
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                mock(RunnerHostService.class),
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                        null, null, true),
                null, null, null, null, null, null, null, null, knowledge);
        when(workspaceService.tree(any(), any())).thenReturn(List.of());
        when(workspaceService.readFile(any(), any(), eq("CLAUDE.md")))
                .thenThrow(new InvalidFilePathException("absent"));
    }

    private Workspace workspace(UUID id) {
        Workspace workspace = new Workspace();
        workspace.setId(id);
        workspace.setUserId(userId);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        return workspace;
    }

    @Test
    @DisplayName("la consigne porte ce qu'on sait déjà de CE client")
    void theSystemPromptCarriesWhatWeKnow() {
        when(knowledge.outlineFor(userId, workspaceA))
                .thenReturn("--- Ce que tu sais déjà de ce client ---\nacces.md — Accès\n");

        assertThat(service.buildSystemPrompt(userId, workspace(workspaceA)))
                .contains("acces.md — Accès");
    }

    @Test
    @DisplayName("LA GARANTIE : la carte d'un poste n'entre JAMAIS dans le tour d'un autre")
    void oneClientsMapNeverLeaksIntoAnothersTurn() {
        // Deux projets du MÊME utilisateur, sur deux postes différents — le cas le plus dangereux,
        // parce que le filtre par compte ne l'attrape pas.
        when(knowledge.outlineFor(userId, workspaceA)).thenReturn("acces.md — bastion lzi de CAGIP\n");
        when(knowledge.outlineFor(userId, workspaceB)).thenReturn("reseau.md — proxy de FREE\n");

        String promptA = service.buildSystemPrompt(userId, workspace(workspaceA));
        String promptB = service.buildSystemPrompt(userId, workspace(workspaceB));

        assertThat(promptA).contains("bastion lzi de CAGIP").doesNotContain("proxy de FREE");
        assertThat(promptB).contains("proxy de FREE").doesNotContain("bastion lzi de CAGIP");
    }

    @Test
    @DisplayName("deux tours sans changement rendent la MÊME consigne, à l'octet (cache)")
    void twoTurnsWithoutChangeProduceTheSamePrompt() {
        when(knowledge.outlineFor(userId, workspaceA)).thenReturn("acces.md — Accès\n");

        assertThat(service.buildSystemPrompt(userId, workspace(workspaceA)))
                .isEqualTo(service.buildSystemPrompt(userId, workspace(workspaceA)));
    }

    @Test
    @DisplayName("sans carte, la consigne est celle d'avant F-136")
    void withoutAMapTheSystemPromptIsUnchanged() {
        when(knowledge.outlineFor(any(), any())).thenReturn(null);

        String prompt = service.buildSystemPrompt(userId, workspace(workspaceA));

        assertThat(prompt).doesNotContain("Ce que tu sais déjà");
        // Les doctrines, elles, restent — on n'a rien retiré.
        assertThat(prompt).contains("Vérifie avant d'affirmer");
    }

    @Test
    @DisplayName("un magasin en panne ne fait JAMAIS échouer un tour")
    void abrokenStoreNeverBreaksATurn() {
        when(knowledge.outlineFor(any(), any()))
                .thenThrow(new IllegalStateException("magasin indisponible"));

        assertThat(service.buildSystemPrompt(userId, workspace(workspaceA)))
                .contains("Vérifie avant d'affirmer");
    }

    @Test
    @DisplayName("composer la consigne ne déclenche aucun rafraîchissement")
    void composingThePromptRefreshesNothing() {
        // Le rafraîchissement appartient à la FIN du tour : le déclencher ici remettrait les six
        // allers-retours vers la machine dans le chemin critique, ce que SF-136-01 évite.
        when(knowledge.outlineFor(any(), any())).thenReturn("acces.md\n");

        service.buildSystemPrompt(userId, workspace(workspaceA));

        verify(knowledge, never()).refreshAfterTurn(any(), any());
    }
}
