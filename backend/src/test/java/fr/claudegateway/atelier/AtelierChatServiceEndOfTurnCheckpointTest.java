package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpoint;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * Le crochet de <b>fin de tour</b> branché dans la boucle (F-50 / SF-50-02).
 *
 * <p>Ce que ces tests protègent : qu'un contrôle bloquant renvoie réellement le modèle au travail —
 * et, tout autant, qu'il ne s'exécute <b>jamais</b> sur un arrêt subi (interruption, réponse coupée,
 * plafond d'étapes), où le déclencher reviendrait à franchir la borne qui vient d'arrêter le tour.</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AtelierChatServiceEndOfTurnCheckpointTest {

    @Mock private WorkspaceService workspaceService;
    @Mock private AtelierMessageRepository messageRepository;
    @Mock private ByokKeyService byokKeyService;
    @Mock private QuotaService quotaService;
    @Mock private fr.claudegateway.git.GitTokenService gitTokenService;
    @Mock private fr.claudegateway.git.GitHubClient gitHubClient;
    @Mock private RunnerToolGateway runnerToolGateway;
    @Mock private fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    @Mock private fr.claudegateway.runner.exec.RunnerConfirmationGate confirmationGate;
    @Mock private fr.claudegateway.runner.audit.RunnerAuditService runnerAuditService;
    @Mock private fr.claudegateway.runner.host.RunnerHostService runnerHostService;

    private StubAiAgentProvider agentProvider;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    /** Contrôle de fin de tour : note ce qu'on lui soumet, et bloque un nombre de fois donné. */
    private static final class EndOfTurnCheckpoint implements AtelierCheckpoint {
        final List<AtelierCheckpointContext> seen = new ArrayList<>();
        private final String correction;
        private int remainingBlocks;

        EndOfTurnCheckpoint(String correction, int blocks) {
            this.correction = correction;
            this.remainingBlocks = blocks;
        }

        @Override
        public AtelierCheckpointKind kind() {
            return AtelierCheckpointKind.END_OF_TURN;
        }

        @Override
        public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
            seen.add(context);
            if (remainingBlocks <= 0) {
                return AtelierCheckpointVerdict.proceed();
            }
            remainingBlocks--;
            return AtelierCheckpointVerdict.block(correction);
        }
    }

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
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
        when(workspaceService.readFile(any(), any(), any())).thenReturn("ancien contenu");
    }

    private AtelierChatService serviceWith(AtelierCheckpoint... checkpoints) {
        return serviceWith(null, checkpoints);
    }

    private AtelierChatService serviceWith(Integer maxIterations, AtelierCheckpoint... checkpoints) {
        return new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(), runnerHostService,
                new AtelierProperties(null, null, null, null, null, null, maxIterations, null, null,
                        null, null, null, true),
                new AtelierCheckpointRunner(List.of(checkpoints)));
    }

    /** Messages utilisateur transmis au fournisseur au dernier appel. */
    private List<String> userTexts() {
        List<String> texts = new ArrayList<>();
        for (AgentMessage message : agentProvider.lastRequest.messages()) {
            if (!"user".equals(message.role())) {
                continue;
            }
            for (AgentContentBlock block : message.content()) {
                if (block instanceof AgentContentBlock.Text text) {
                    texts.add(text.text());
                }
            }
        }
        return texts;
    }

    @Test
    void aBlockingCheckpointSendsTheModelBackToWork() {
        EndOfTurnCheckpoint checkpoint =
                new EndOfTurnCheckpoint("Renseigne le fichier STATE.md, puis conclus.", 1);
        AtelierChatService service = serviceWith(checkpoint);
        agentProvider.enqueueFinal("C'est fait.");
        agentProvider.enqueueFinal("Voilà, STATE.md est à jour.");

        AtelierChatResult result = service.chat(userId, workspaceId, "range le projet");

        // La réponse rendue est celle du DERNIER tour, jamais celle que le contrôle a refusée (D4).
        assertThat(result.reply()).isEqualTo("Voilà, STATE.md est à jour.");
        assertThat(checkpoint.seen).hasSize(2);
        // La correction est déposée côté utilisateur : il n'y a aucun appel d'outil à qui la
        // rattacher (D1).
        assertThat(userTexts()).contains(
                "Fin de tour contrôlée : Renseigne le fichier STATE.md, puis conclus.");
        // Le tour refusé est rejoué : le modèle doit voir ce qu'il venait de dire.
        assertThat(agentProvider.lastRequest.messages()).anySatisfy(message -> {
            assertThat(message.role()).isEqualTo("assistant");
            assertThat(message.content()).anySatisfy(block ->
                    assertThat(((AgentContentBlock.Text) block).text()).isEqualTo("C'est fait."));
        });
    }

    @Test
    void theCheckpointSeesTheReplyAndTheFilesWrittenDuringTheTurn() {
        EndOfTurnCheckpoint checkpoint = new EndOfTurnCheckpoint(null, 0);
        AtelierChatService service = serviceWith(checkpoint);
        agentProvider.enqueueToolCall("write_file", "path", "a.txt", "content", "un");
        agentProvider.enqueueToolCall("write_file", "path", "a.txt", "content", "deux");
        agentProvider.enqueueToolCall("edit_file", "path", "b.txt",
                "old_string", "ancien", "new_string", "nouveau");
        agentProvider.enqueueToolCall("read_file", "path", "c.txt");
        agentProvider.enqueueFinal("Terminé.");

        service.chat(userId, workspaceId, "travaille");

        assertThat(checkpoint.seen).hasSize(1);
        AtelierCheckpointContext context = checkpoint.seen.get(0);
        assertThat(context.kind()).isEqualTo(AtelierCheckpointKind.END_OF_TURN);
        // Isolation : le couple du tour, déjà vérifié possédé.
        assertThat(context.userId()).isEqualTo(userId);
        assertThat(context.workspaceId()).isEqualTo(workspaceId);
        assertThat(context.replyText()).isEqualTo("Terminé.");
        // Ordre d'écriture, sans doublon, et jamais le fichier seulement lu.
        assertThat(context.writtenPaths()).containsExactly("a.txt", "b.txt");
    }

    @Test
    void theRealJudgeSendsATurnWithoutItsMarkerBackToWork() {
        // Bout en bout avec le contrôle réel du premier paquet (F-52 / SF-52-02) : une réponse sans
        // marqueur repart, la même réponse marquée s'arrête.
        fr.claudegateway.governance.control.JugeFinDeTourControl juge =
                new fr.claudegateway.governance.control.JugeFinDeTourControl();
        // Un contrôle de gouvernance n'est pas un crochet : il y arrive par la délégation de
        // SF-51-04. On l'adapte ici pour l'observer dans la boucle, sans monter tout le catalogue.
        AtelierChatService service = serviceWith(new AtelierCheckpoint() {
            @Override
            public AtelierCheckpointKind kind() {
                return AtelierCheckpointKind.END_OF_TURN;
            }

            @Override
            public AtelierCheckpointVerdict evaluate(AtelierCheckpointContext context) {
                return juge.evaluate(context);
            }
        });
        agentProvider.enqueueFinal("C'est fait.");
        agentProvider.enqueueFinal("C'est fait.\n\n"
                + fr.claudegateway.governance.control.FinDeTourMarker.FORME);

        AtelierChatResult result = service.chat(userId, workspaceId, "range le projet");

        assertThat(result.reply()).contains("fin-de-tour");
        assertThat(userTexts()).anySatisfy(text -> assertThat(text)
                .startsWith("Fin de tour contrôlée : ")
                .contains(fr.claudegateway.governance.control.FinDeTourMarker.FORME));
    }

    @Test
    void aPassingCheckpointEndsTheTurnAsBefore() {
        EndOfTurnCheckpoint checkpoint = new EndOfTurnCheckpoint(null, 0);
        AtelierChatService service = serviceWith(checkpoint);
        agentProvider.enqueueFinal("C'est fait.");

        AtelierChatResult result = service.chat(userId, workspaceId, "range le projet");

        assertThat(result.reply()).isEqualTo("C'est fait.");
        assertThat(checkpoint.seen).hasSize(1);
    }

    @Test
    void withoutAnyCheckpointTheTurnEndsExactlyAsBefore() {
        AtelierChatService service = serviceWith();
        agentProvider.enqueueFinal("C'est fait.");

        assertThat(service.chat(userId, workspaceId, "range").reply()).isEqualTo("C'est fait.");
    }

    @Test
    void aCheckpointThatAlwaysBlocksStillGivesTheTurnBack() {
        // Borne dure (D2) : sans elle, une règle mal écrite ferait tourner le message jusqu'au
        // plafond d'étapes, aux frais de l'utilisateur.
        EndOfTurnCheckpoint checkpoint = new EndOfTurnCheckpoint("Recommence.", 99);
        AtelierChatService service = serviceWith(checkpoint);
        for (int i = 0; i < 10; i++) {
            agentProvider.enqueueFinal("Réponse " + i);
        }

        AtelierChatResult result = service.chat(userId, workspaceId, "range");

        // Au troisième blocage, la main est rendue : le contrôle n'est même plus interrogé.
        assertThat(checkpoint.seen).hasSize(AtelierChatService.MAX_END_OF_TURN_BLOCKS);
        assertThat(result.reply()).isEqualTo("Réponse " + AtelierChatService.MAX_END_OF_TURN_BLOCKS);
    }

    @Test
    void anInterruptedTurnIsNeverControlled() {
        EndOfTurnCheckpoint checkpoint = new EndOfTurnCheckpoint("Ne devrait jamais être rendu.", 9);
        AtelierChatService service = serviceWith(checkpoint);
        agentProvider.enqueueToolCall("read_file", "path", "a.txt");
        agentProvider.enqueueFinal("Fini.");
        agentProvider.onTurn(() -> service.interruptChat(userId, workspaceId));

        AtelierChatResult result = service.chat(userId, workspaceId, "travaille");

        assertThat(result.reply()).isEqualTo(AtelierChatService.INTERRUPTED_REPLY);
        assertThat(checkpoint.seen).isEmpty();
    }

    @Test
    void aTruncatedAnswerIsNeverControlled() {
        EndOfTurnCheckpoint checkpoint = new EndOfTurnCheckpoint("Ne devrait jamais être rendu.", 9);
        AtelierChatService service = serviceWith(checkpoint);
        agentProvider.enqueueTruncated("Je vais écrire…", "write_file");

        AtelierChatResult result = service.chat(userId, workspaceId, "écris");

        assertThat(result.reply()).isEqualTo(AtelierChatService.TRUNCATED_REPLY);
        assertThat(checkpoint.seen).isEmpty();
    }

    @Test
    void theStepCeilingIsNeverControlledEither() {
        // Le tour s'arrête parce qu'il a épuisé ses étapes : le renvoyer au travail relèverait le
        // plafond, ce que le crochet ne doit jamais faire.
        EndOfTurnCheckpoint checkpoint = new EndOfTurnCheckpoint("Ne devrait jamais être rendu.", 9);
        AtelierChatService service = serviceWith(2, checkpoint);
        agentProvider.enqueueToolCall("read_file", "path", "a.txt");
        agentProvider.enqueueToolCall("read_file", "path", "b.txt");
        agentProvider.enqueueFinal("Fini.");

        service.chat(userId, workspaceId, "travaille");

        assertThat(checkpoint.seen).isEmpty();
    }

    @Test
    void theBlockIsVisibleAgainAfterAReload() {
        EndOfTurnCheckpoint checkpoint = new EndOfTurnCheckpoint("Renseigne STATE.md.", 1);
        AtelierChatService service = serviceWith(checkpoint);
        agentProvider.enqueueFinal("C'est fait.");
        agentProvider.enqueueFinal("Voilà.");

        service.chat(userId, workspaceId, "range");

        ArgumentCaptor<AtelierMessage> saved = ArgumentCaptor.forClass(AtelierMessage.class);
        org.mockito.Mockito.verify(messageRepository, org.mockito.Mockito.atLeastOnce())
                .save(saved.capture());
        AtelierMessage assistant = saved.getAllValues().stream()
                .filter(message -> "ASSISTANT".equals(message.getRole()))
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(assistant.getTerminalJson())
                .contains("point de contrôle")
                .contains("Fin de tour contrôlée : Renseigne STATE.md.");
    }
}
