package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner;
import fr.claudegateway.atelier.storage.InMemoryWorkspaceStorage;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.teams.TeamsToolCatalog;
import fr.claudegateway.teams.block.TeamsBlockCard;
import fr.claudegateway.teams.block.TeamsMomentImageService;

/**
 * <b>Un bloc riche se pose dans le fil — et seulement dans un terminal Teams</b> (F-89 / SF-89-02).
 *
 * <p>Deux verrous tiennent la règle non négociable du cadrage, et ces tests couvrent le
 * <b>second</b> : les outils de présentation ne sont pas <i>déclarés</i> hors d'un terminal Teams
 * (premier verrou, {@code TeamsToolCatalogTest}), et s'ils sont tout de même <i>appelés</i>, ils sont
 * refusés. Les deux ne sont pas redondants : la boucle relaie les outils non déclarés au lieu de les
 * refuser d'emblée, et un modèle peut parfaitement nommer un outil qu'on ne lui a pas donné.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceTeamsBlockTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private RunnerToolGateway runnerToolGateway;
    @Mock private RunnerCallDispatcher runnerCallDispatcher;
    @Mock private RunnerConfirmationGate confirmationGate;
    @Mock private RunnerAuditService runnerAuditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;
    private TeamsMomentImageService momentImages;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    /** Ce que le listener a vu passer : c'est le relais au fil de l'eau. */
    private final List<TeamsBlockCard> relayed = new ArrayList<>();

    private static final String CARD_INPUT = """
            {
              "title": "Comité de migration",
              "window": "du 5 au 12 septembre, 47 messages lus, 3 non reconnus",
              "gaps": ["3 messages non reconnus"],
              "sections": [{"title": "Ce qu'on attend de vous", "lines": [
                 {"text": "Fournir le schéma réseau", "author": "Paul",
                  "at": "2026-09-12T14:32:00Z", "messageId": "m-1",
                  "webUrl": "https://teams.microsoft.com/l/message/m-1",
                  "certainty": "EXPLICITE"}]}]
            }
            """;

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        momentImages = new TeamsMomentImageService(new InMemoryWorkspaceStorage());
        service = new AtelierChatService(workspaceService, messageRepository,
                (AiAgentProvider) agentProvider, byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient,
                        new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                        null, null, true),
                AtelierCheckpointRunner.none(), ProjectRulesSource.NONE,
                TeamsToolCatalog.none(), momentImages);

        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(java.util.Optional.empty());
        when(quotaService.currentUsage(userId)).thenReturn(
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
    }

    private Workspace terminal(boolean teams) {
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        workspace.setExecutionTarget(WorkspaceExecutionTarget.SANDBOX);
        workspace.setTeamsTerminal(teams);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        return workspace;
    }

    private AtelierProgressListener listener() {
        return new AtelierProgressListener() {
            @Override
            public void onAction(AtelierStepEvent step) {
                // rien
            }

            @Override
            public void onText(String text) {
                // rien
            }

            @Override
            public void onCard(String toolUseId, TeamsBlockCard card) {
                relayed.add(card);
            }
        };
    }

    /**
     * Les blocs <b>réellement persistés</b>, relus comme l'écran les relit : par le JSON de
     * {@code terminal_json}. Passer par le JSON n'est pas un détail — c'est le seul moyen de
     * prouver que la carte survit à la sérialisation.
     */
    private List<AtelierTurnReport.Block> persistedBlocks() {
        org.mockito.ArgumentCaptor<AtelierMessage> captor =
                org.mockito.ArgumentCaptor.forClass(AtelierMessage.class);
        org.mockito.Mockito.verify(messageRepository, org.mockito.Mockito.atLeastOnce())
                .save(captor.capture());
        AtelierMessage assistant = captor.getAllValues().stream()
                .filter(message -> "ASSISTANT".equals(message.getRole()))
                .reduce((first, second) -> second)
                .orElseThrow();
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root =
                    mapper.readTree(assistant.getTerminalJson());
            List<AtelierTurnReport.Block> blocks = new ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode block : root.path("blocks")) {
                blocks.add(mapper.treeToValue(block, AtelierTurnReport.Block.class));
            }
            return blocks;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("dans un terminal Teams, la carte est posée, relayée, et écrite dans la transcription")
    void aCardIsPostedRelayedAndPersisted() {
        terminal(true);
        agentProvider.enqueueToolCallWithObject(TeamsToolCatalog.MEETING_CARD, CARD_INPUT);
        agentProvider.enqueueFinal("Voilà le compte rendu.");

        service.chatStreaming(userId, workspaceId, "résume la réunion d'hier", listener());

        // Relayé au fil de l'eau : on ne découvre pas un compte rendu à la fin d'un tour.
        assertThat(relayed).hasSize(1);
        assertThat(relayed.get(0).title()).isEqualTo("Comité de migration");
        assertThat(relayed.get(0).kind()).isEqualTo(TeamsBlockCard.Kind.MEETING_CARD);

        // Et écrit dans la transcription : il survit au rechargement, comme le reste du fil.
        List<AtelierTurnReport.Block> blocks = persistedBlocks();
        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.tool()).isEqualTo(TeamsToolCatalog.MEETING_CARD);
            assertThat(block.card()).isNotNull();
            assertThat(block.card().allLines()).hasSize(1);
            assertThat(block.card().allLines().get(0).webUrl())
                    .isEqualTo("https://teams.microsoft.com/l/message/m-1");
        });
    }

    @Test
    @DisplayName("UN TERMINAL DE PROJET RESTE TEXTUEL : l'appel est refusé, et AUCUN bloc n'est écrit")
    void aProjectTerminalStaysTextualForever() {
        terminal(false);
        agentProvider.enqueueToolCallWithObject(TeamsToolCatalog.MEETING_CARD, CARD_INPUT);
        agentProvider.enqueueFinal("Je réponds en clair.");

        service.chatStreaming(userId, workspaceId, "fais-moi une carte", listener());

        assertThat(relayed).as("aucun bloc relayé hors d'un terminal Teams").isEmpty();
        List<AtelierTurnReport.Block> blocks = persistedBlocks();
        assertThat(blocks).allSatisfy(block -> assertThat(block.card())
                .as("une sortie de commande est exactement ce que la machine a répondu, jamais une carte")
                .isNull());
        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.error()).isTrue();
            assertThat(block.output()).contains("n'affiche que du texte");
        });
    }

    @Test
    @DisplayName("un bloc refusé ne se pose pas à moitié : rien n'est relayé, et l'agent reçoit le motif")
    void aRejectedBlockPostsNothing() {
        terminal(true);
        agentProvider.enqueueToolCallWithObject(TeamsToolCatalog.MEETING_CARD, """
                {"title": "Comité", "window": "7 jours", "gaps": [],
                 "sections": [{"title": "Décisions", "lines": [{"text": "On décale au T3"}]}]}
                """);
        agentProvider.enqueueFinal("Je reprends avec les sources.");

        service.chatStreaming(userId, workspaceId, "résume", listener());

        assertThat(relayed).isEmpty();
        List<AtelierTurnReport.Block> blocks = persistedBlocks();
        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.error()).isTrue();
            assertThat(block.output()).contains("n'a pas de source");
        });
    }

    @Test
    @DisplayName("le compte rendu rendu au modèle dit ce qui a été retenu, explicite et à confirmer")
    void theModelIsToldWhatWasKept() {
        terminal(true);
        agentProvider.enqueueToolCallWithObject(TeamsToolCatalog.LIST, """
                {"title": "Vos engagements", "window": "du 5 au 12 septembre", "gaps": [],
                 "lines": [
                   {"text": "Envoyer le devis", "messageId": "m-1", "certainty": "EXPLICITE"},
                   {"text": "Relancer Paul", "messageId": "m-2"}]}
                """);
        agentProvider.enqueueFinal("Voilà.");

        service.chatStreaming(userId, workspaceId, "qu'est-ce que j'ai promis ?", listener());

        List<AtelierTurnReport.Block> blocks = persistedBlocks();
        assertThat(blocks).anySatisfy(block -> {
            assertThat(block.error()).isFalse();
            assertThat(block.output())
                    .contains("2 ligne(s)")
                    .contains("1 explicite(s)")
                    .contains("1 à confirmer")
                    .contains("Aucun manque déclaré");
        });
    }

    @Test
    @DisplayName("un moment qui nomme une image inconnue fait refuser : on n'invente pas d'identifiant")
    void anUnknownImageRefusesTheMoment() {
        terminal(true);
        agentProvider.enqueueToolCallWithObject(TeamsToolCatalog.MOMENTS, """
                {"title": "Comité", "window": "réunion du 12", "gaps": [],
                 "moments": [{"at": "2026-09-12T14:32:00Z", "quote": "on décale",
                              "imageId": "inventee"}]}
                """);
        agentProvider.enqueueFinal("Bon.");

        service.chatStreaming(userId, workspaceId, "montre-moi", listener());

        assertThat(relayed).isEmpty();
    }

    @Test
    @DisplayName("un moment dont l'image EXISTE pour ce terminal est accepté")
    void aKnownImageIsAccepted() {
        terminal(true);
        String imageId = momentImages.store(userId, workspaceId, "image/png",
                "png".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        agentProvider.enqueueToolCallWithObject(TeamsToolCatalog.MOMENTS, """
                {"title": "Comité", "window": "réunion du 12", "gaps": [],
                 "moments": [{"at": "2026-09-12T14:32:00Z", "quote": "on décale",
                              "imageId": "%s"}]}
                """.formatted(imageId));
        agentProvider.enqueueFinal("Voilà.");

        service.chatStreaming(userId, workspaceId, "montre-moi", listener());

        assertThat(relayed).hasSize(1);
        assertThat(relayed.get(0).moments()).hasSize(1);
        assertThat(relayed.get(0).moments().get(0).imageId()).isEqualTo(imageId);
    }
}
