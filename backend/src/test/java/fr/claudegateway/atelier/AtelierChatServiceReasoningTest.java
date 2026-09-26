package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentReasoning;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;

/**
 * Raisonnement de la boucle maison (F-39 / SF-39-10) : le modèle est celui du harnais, le tour
 * demande un raisonnement adaptatif, et les blocs signés rendus par le fournisseur sont remis en
 * tête du message assistant — mais ne survivent pas au tour.
 */
@ExtendWith(MockitoExtension.class)
class AtelierChatServiceReasoningTest {

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

    private StubAiAgentProvider agentProvider;
    private AtelierChatService service;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final List<AtelierMessage> history = new ArrayList<>();
    private final List<AtelierMessage> saved = new ArrayList<>();

    @BeforeEach
    void setUp() {
        agentProvider = new StubAiAgentProvider();
        buildService(new AtelierProperties(null, null, null, null, null, null, null, null, null, null, null, null, true));

        Workspace workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
        workspace.setSource(WorkspaceSource.ARCHIVE);
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(byokKeyService.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        // Quota lu pour dériver le plafond de consommation du message (F-39 / SF-39-15).
        org.mockito.Mockito.lenient().when(quotaService.currentUsage(userId)).thenReturn(
                new fr.claudegateway.quota.UsageSnapshot(0L, 12_000_000L, 12_000_000L, null, null));
        lenient().when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(history);
        when(messageRepository.save(any(AtelierMessage.class))).thenAnswer(invocation -> {
            AtelierMessage message = invocation.getArgument(0);
            if (message.getId() == null) {
                message.setId(UUID.randomUUID());
            }
            saved.add(message);
            return message;
        });
        lenient().when(workspaceService.tree(any(), any())).thenReturn(List.of());
        lenient().when(workspaceService.readFile(any(), any(), any())).thenReturn("contenu");
    }

    private void buildService(AtelierProperties properties) {
        service = new AtelierChatService(workspaceService, messageRepository, (AiAgentProvider) agentProvider,
                byokKeyService, quotaService,
                new fr.claudegateway.atelier.git.GitWorkspaceService(workspaceService, gitTokenService,
                        gitHubClient, new fr.claudegateway.git.GitProperties(null, null, null, null, null, null)),
                runnerToolGateway, runnerCallDispatcher, confirmationGate, runnerAuditService,
                fr.claudegateway.runner.relay.RunnerRelayBroadcaster.disabled(),
                runnerHostService,
                properties);
    }

    private AtelierMessage lastSavedAssistant() {
        return saved.stream().filter(m -> "ASSISTANT".equals(m.getRole())).reduce((a, b) -> b).orElseThrow();
    }

    @Test
    void sendsTheHarnessModelAndAsksForAdaptiveReasoning() {
        agentProvider.enqueueFinal("Bonjour.");

        service.chat(userId, workspaceId, "bonjour");

        // Le modèle de la boucle est le sien (D-L5-1) : plus celui que le chat propose par défaut.
        assertThat(agentProvider.lastRequest.model()).isEqualTo("claude-opus-5");
        assertThat(agentProvider.lastRequest.reasoning()).isEqualTo(new AgentReasoning(true, "high"));
    }

    @Test
    void honoursTheConfiguredModelAndEffort() {
        buildService(new AtelierProperties(null, null, null, null, null, null, null,
                "claude-opus-4-8", "xhigh", null, null, null, true));
        agentProvider.enqueueFinal("Bonjour.");

        service.chat(userId, workspaceId, "bonjour");

        assertThat(agentProvider.lastRequest.model()).isEqualTo("claude-opus-4-8");
        assertThat(agentProvider.lastRequest.reasoning()).isEqualTo(new AgentReasoning(true, "xhigh"));
    }

    @Test
    void replaysTheReasoningOfTheTurnAheadOfItsTextAndToolCalls() {
        agentProvider.enqueueToolCallWithReasoning("read_file", "sig-1", "path", "notes.txt");
        agentProvider.enqueueFinal("J'ai lu notes.txt.");

        service.chat(userId, workspaceId, "lis notes.txt");

        // Le fournisseur exige de retrouver ses blocs signés, inchangés et EN TÊTE, sur le dernier
        // tour d'assistant quand on lui renvoie les tool_result (D-L5-3).
        //
        // Le dernier message assistant n'est plus forcément l'avant-dernier depuis F-134 /
        // SF-134-05 : une consigne d'effort peut s'être glissée entre lui et les résultats
        // d'outils. On le cherche donc par son rôle — ce que le test aurait dû faire dès l'origine.
        List<AgentMessage> sent = agentProvider.lastRequest.messages();
        AgentMessage assistant = sent.stream()
                .filter(message -> "assistant".equals(message.role()))
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(assistant.role()).isEqualTo("assistant");
        assertThat(assistant.content().get(0))
                .isEqualTo(new AgentContentBlock.Reasoning("", "sig-1"));
        assertThat(assistant.content().get(1)).isEqualTo(new AgentContentBlock.Text("je regarde"));
        assertThat(assistant.content().get(2)).isInstanceOf(AgentContentBlock.ToolUse.class);
    }

    @Test
    void keepsTheReasoningInTheTraceSoTheNextTurnCanReplayIt() {
        // DÉCISION RENVERSÉE, F-134 / SF-134-04. La trajectoire ne gardait RIEN du raisonnement :
        // « il vit le temps d'un tour ». L'intention était sage — ne pas rejouer un bloc signé hors
        // de son contexte.
        //
        // Mais la conséquence ne se voyait pas : pendant le tour, le message assistant envoyé au
        // fournisseur commence par ses blocs de raisonnement, et c'est CET ensemble qu'il met en
        // cache. En les omettant au rejeu, on lui renvoyait un ruban qui différait du sien dès le
        // premier bloc de chaque tour — tout le reste était réécrit au double du tarif d'entrée.
        // Mesuré : 23 % de contexte relu sur un fil de deux tours, 98 % du coût d'un tour en
        // écriture de cache.
        //
        // Sur le modèle servi, les blocs de raisonnement des tours précédents sont PRÉSERVÉS par
        // le fournisseur : les lui renvoyer inchangés est ce qu'il attend. Ce qu'il refuse, c'est
        // un bloc RETOUCHÉ — et le rejeu ne les retouche pas, il les recopie.
        agentProvider.enqueueToolCallWithReasoning("read_file", "sig-1", "path", "notes.txt");
        agentProvider.enqueueFinal("J'ai lu notes.txt.");

        service.chat(userId, workspaceId, "lis notes.txt");

        String trace = lastSavedAssistant().getToolTrace();
        assertThat(trace).contains("sig-1");
    }

    @Test
    void theReasoningIsNeverExposedOutsideTheProviderLoop() {
        // La contrepartie de la décision ci-dessus : le raisonnement est conservé POUR LE REJEU,
        // pas pour être lu. Il ne part ni dans la réponse rendue au client, ni dans le relevé du
        // tour — seule la trajectoire, qui ne sort jamais telle quelle, le porte.
        agentProvider.enqueueToolCallWithReasoning("read_file", "sig-1", "path", "notes.txt");
        agentProvider.enqueueFinal("J'ai lu notes.txt.");

        AtelierChatService.AtelierChatResult result =
                service.chat(userId, workspaceId, "lis notes.txt");

        assertThat(result.reply()).doesNotContain("sig-1");
        assertThat(lastSavedAssistant().getTerminalJson()).doesNotContain("sig-1");
    }

    // ------------------------------------------- F-118 / SF-118-01 : effort adaptatif à l'étape

    @Test
    void aSingleStepDemandKeepsTheNormalEffort() {
        // Une demande neuve résolue en un seul tour part avec l'effort normal (`high`) : c'est le
        // tour où la réflexion sert.
        agentProvider.enqueueFinal("Bonjour.");

        service.chat(userId, workspaceId, "bonjour");

        assertThat(agentProvider.effectiveEfforts).containsExactly("high");
    }

    @Test
    void continuationStepsStartWithTheReducedEffort() {
        // Premier tour (cadrage) : effort normal `high`. Étape de continuation (relire le fichier
        // après l'appel d'outil) : effort réduit `medium` — enchaîner un outil n'a pas besoin de
        // « réfléchir fort ». Le raisonnement adaptatif reste actif sur les deux tours.
        agentProvider.enqueueToolCall("read_file", "path", "notes.txt");
        agentProvider.enqueueFinal("J'ai lu notes.txt.");

        service.chat(userId, workspaceId, "lis notes.txt");

        assertThat(agentProvider.effectiveEfforts).containsExactly("high", "medium");
    }

    // ------------------------------- F-134 / SF-134-05 : l'effort voyage dans la conversation

    @Test
    void theTopLevelEffortNeverChangesWithinATurn() {
        // LA PROPRIÉTÉ QUI FAIT TENIR LE CACHE. Le réglage à la racine de la requête est rendu
        // AVANT la conversation : le changer invalide tout le cache des messages. Il doit donc
        // rester constant, pendant que le niveau EFFECTIF, lui, varie comme avant.
        agentProvider.enqueueToolCall("read_file", "path", "notes.txt");
        agentProvider.enqueueFinal("J'ai lu notes.txt.");

        service.chat(userId, workspaceId, "lis notes.txt");

        assertThat(agentProvider.reasoningSnapshots)
                .extracting(AgentReasoning::effort)
                .containsOnly("high");
        // …alors que le niveau effectif, lui, baisse bien à l'étape de continuation.
        assertThat(agentProvider.effectiveEfforts).containsExactly("high", "medium");
    }

    @Test
    void theDirectiveIsGlidedBeforeTheMessageThatTriggersTheAnswer() {
        // Le niveau prend effet « à partir du prochain tour utilisateur » : la consigne doit donc
        // précéder les résultats d'outils, qui sont ce message. Posée après, elle ne vaudrait que
        // pour le tour suivant.
        agentProvider.enqueueToolCall("read_file", "path", "notes.txt");
        agentProvider.enqueueFinal("J'ai lu notes.txt.");

        service.chat(userId, workspaceId, "lis notes.txt");

        List<AgentMessage> sent = agentProvider.lastRequest.messages();
        int directive = -1;
        for (int i = 0; i < sent.size(); i++) {
            if (sent.get(i).isEffortDirective()) {
                directive = i;
            }
        }
        assertThat(directive).as("une consigne d'effort a été glissée").isNotEqualTo(-1);
        assertThat(sent.get(directive).content()).isEmpty();
        assertThat(sent.get(directive).effort()).isEqualTo("medium");
        // Elle précède bien le dernier message — celui qui déclenche la réponse.
        assertThat(directive).isEqualTo(sent.size() - 2);
        assertThat(sent.get(sent.size() - 1).role()).isEqualTo("user");
    }

    @Test
    void noDirectiveWhenTheLevelDoesNotChange() {
        // Un message de plus est un octet de plus dans le ruban, et le ruban est ce qu'on cherche
        // à garder stable : on ne glisse une consigne que lorsque le niveau change vraiment.
        agentProvider.enqueueFinal("Bonjour.");

        service.chat(userId, workspaceId, "bonjour");

        assertThat(agentProvider.lastRequest.messages())
                .noneMatch(AgentMessage::isEffortDirective);
    }

    @Test
    void theFallbackFlagRestoresTheFlatEffort() {
        // Coupe-circuit `adaptive-effort=false` : l'effort normal est appliqué à CHAQUE étape,
        // comportement d'avant F-118, sans livraison. `storageExecution=true` (13e arg) pour que la
        // boucle parte, comme la config par défaut de ce test.
        buildService(new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, true, null, null, false));
        agentProvider.enqueueToolCall("read_file", "path", "notes.txt");
        agentProvider.enqueueFinal("J'ai lu notes.txt.");

        service.chat(userId, workspaceId, "lis notes.txt");

        assertThat(agentProvider.effectiveEfforts).containsExactly("high", "high");
    }

    // ------------------------------------------- F-119 / SF-119-01 : ré-escalade de l'effort sur signal

    @Test
    void aToolErrorMakesTheNextStepRegainNormalEffort() {
        // Un résultat d'outil EN ERREUR (ici un outil inconnu) au premier tour fait remonter l'effort
        // au NORMAL (`high`) au tour de continuation, au lieu de rester à `low`. C'est le cœur de
        // F-119 : c'est APRÈS un incident qu'il faut réfléchir le plus.
        agentProvider.enqueueToolCall("frobnicate");
        agentProvider.enqueueFinal("Corrigé.");

        service.chat(userId, workspaceId, "fais un truc");

        assertThat(agentProvider.effectiveEfforts).containsExactly("high", "high");
    }

    @Test
    void aSelfContradictionInTheTurnTextRegainsNormalEffort() {
        // Le modèle se dédit dans son texte ("je me suis trompé") tout en enchaînant un outil : le
        // tour suivant remonte au NORMAL, même si l'outil lui-même a réussi.
        agentProvider.enqueueToolCallWithText("Je me suis trompé, je relis.", "read_file",
                "path", "notes.txt");
        agentProvider.enqueueFinal("Voilà la bonne réponse.");

        service.chat(userId, workspaceId, "lis notes.txt");

        assertThat(agentProvider.effectiveEfforts).containsExactly("high", "high");
    }

    @Test
    void aCleanContinuationKeepsTheReducedEffortDespiteTheSignalPath() {
        // Non-régression du gain F-118 : un enchaînement SANS incident (outil qui réussit, aucun
        // marqueur d'auto-contradiction) garde l'effort réduit `medium` au tour de continuation.
        agentProvider.enqueueToolCall("read_file", "path", "notes.txt");
        agentProvider.enqueueFinal("J'ai lu notes.txt.");

        service.chat(userId, workspaceId, "lis notes.txt");

        assertThat(agentProvider.effectiveEfforts).containsExactly("high", "medium");
    }

    @Test
    void theEscalateOnSignalFlagCanBeTurnedOff() {
        // Coupe-circuit `escalate-on-signal=false` (19e arg) : comportement F-118 strict — l'effort
        // reste réduit sur la continuation MALGRÉ l'erreur d'outil. `storageExecution=true` (13e arg)
        // pour que la boucle parte.
        buildService(new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, true, null, null, null, null, null, false));
        agentProvider.enqueueToolCall("frobnicate");
        agentProvider.enqueueFinal("Tant pis.");

        service.chat(userId, workspaceId, "fais un truc");

        assertThat(agentProvider.effectiveEfforts).containsExactly("high", "medium");
    }

    // ------------------------------------------- F-121 / SF-121-08 : plafond de la ré-escalade

    /**
     * Forme canonique (26 composants) réglée pour ces tests : {@code storageExecution=true} (13ᵉ) pour
     * que la boucle parte, {@code escalateOnSignal} (19ᵉ) et {@code escalateEffort} (26ᵉ) au choix.
     */
    private void buildWithEscalationCeiling(Boolean escalateOnSignal, String escalateEffort) {
        buildService(new AtelierProperties(null, null, null, null, null, null, null, null, null, null,
                null, null, true, null, null, null, null, null, escalateOnSignal, null, null, null,
                null, null, null, escalateEffort));
    }

    @Test
    void aToolErrorEscalatesUpToTheConfiguredCeiling() {
        // Le cœur de SF-121-08 : l'escalade F-119 était plafonnée à l'effort NORMAL (`high`). Réglée
        // à `xhigh`, elle monte vraiment — mais seulement sur le tour qui suit l'incident.
        buildWithEscalationCeiling(null, "xhigh");
        agentProvider.enqueueToolCall("frobnicate");
        agentProvider.enqueueFinal("Corrigé.");

        service.chat(userId, workspaceId, "fais un truc");

        assertThat(agentProvider.effectiveEfforts).containsExactly("high", "xhigh");
    }

    @Test
    void aCleanContinuationIgnoresTheCeiling() {
        // Non-régression du gain F-118 : sans incident, la continuation reste à l'effort réduit. Le
        // plafond n'ouvre QUE le chemin d'escalade — et le premier tour reste au régime ordinaire.
        buildWithEscalationCeiling(null, "max");
        agentProvider.enqueueToolCall("read_file", "path", "notes.txt");
        agentProvider.enqueueFinal("J'ai lu notes.txt.");

        service.chat(userId, workspaceId, "lis notes.txt");

        assertThat(agentProvider.effectiveEfforts).containsExactly("high", "medium");
    }

    @Test
    void theEscalateOnSignalFlagAlsoNeutralisesTheCeiling() {
        // Le coupe-circuit F-119 reste le maître : aucune ré-escalade, donc aucun plafond à atteindre.
        buildWithEscalationCeiling(false, "max");
        agentProvider.enqueueToolCall("frobnicate");
        agentProvider.enqueueFinal("Tant pis.");

        service.chat(userId, workspaceId, "fais un truc");

        assertThat(agentProvider.effectiveEfforts).containsExactly("high", "medium");
    }

    @Test
    void withoutTheSettingTheEscalationStaysAtTheNormalEffort() {
        // Non-régression stricte : réglage absent => l'escalade vaut l'effort normal, exactement comme
        // avant SF-121-08 (même assertion que le test F-119 d'origine, par la forme canonique).
        buildWithEscalationCeiling(null, null);
        agentProvider.enqueueToolCall("frobnicate");
        agentProvider.enqueueFinal("Corrigé.");

        service.chat(userId, workspaceId, "fais un truc");

        assertThat(agentProvider.effectiveEfforts).containsExactly("high", "high");
    }

    @Test
    void aReplayedHistoryCarriesNoReasoningBlock() {
        history.add(AtelierMessage.builder().id(UUID.randomUUID()).workspaceId(workspaceId).userId(userId)
                .role("USER").content("lis notes.txt").build());
        history.add(AtelierMessage.builder().id(UUID.randomUUID()).workspaceId(workspaceId).userId(userId)
                .role("ASSISTANT").content("J'ai lu notes.txt.")
                .toolTrace(new AtelierToolTrace(List.of(new AtelierToolTrace.Step("je lis",
                        List.of(new AtelierToolTrace.Call("call_1", "read_file", null, "contenu", false)))))
                        .toJson())
                .build());
        agentProvider.enqueueFinal("Compris.");

        service.chat(userId, workspaceId, "et maintenant ?");

        assertThat(agentProvider.lastRequest.messages())
                .flatExtracting(AgentMessage::content)
                .noneMatch(block -> block instanceof AgentContentBlock.Reasoning
                        || block instanceof AgentContentBlock.RedactedReasoning);
    }
}
