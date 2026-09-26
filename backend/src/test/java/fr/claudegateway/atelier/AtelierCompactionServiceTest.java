package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.agent.StubAiAgentProvider;

/**
 * Compaction automatique du fil d'Atelier (F-117 / SF-117-01) : quand le texte rejoué dépasse le
 * seuil, les tours anciens sont résumés et la frontière avancée — les tours récents restent entiers,
 * l'affichage n'est jamais touché.
 */
@ExtendWith(MockitoExtension.class)
class AtelierCompactionServiceTest {

    @Mock private AtelierMessageRepository messageRepository;
    @Mock private WorkspaceRepository workspaceRepository;

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        workspace = new Workspace();
        workspace.setId(workspaceId);
        workspace.setUserId(userId);
    }

    /** Service câblé sur un fournisseur donné, avec un seuil bas et 2 messages récents gardés. */
    private AtelierCompactionService service(AiAgentProvider provider) {
        AtelierCompactionProperties props = new AtelierCompactionProperties(true, 100, 2);
        AtelierProperties atelier = new AtelierProperties(null, null, null, null, null, null, null,
                null, null, null, null, null, null, true);
        return new AtelierCompactionService(messageRepository, workspaceRepository, provider, props,
                atelier);
    }

    private AtelierMessage message(String role, String content, OffsetDateTime at) {
        return AtelierMessage.builder().id(UUID.randomUUID()).workspaceId(workspaceId).userId(userId)
                .role(role).content(content).createdAt(at).build();
    }

    /** Texte assez long pour franchir le seuil (100 tokens ≈ 400 caractères). */
    private String longText(String prefix) {
        return prefix + " " + "x".repeat(500);
    }

    private void stubHistory(List<AtelierMessage> history) {
        when(messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId))
                .thenReturn(history);
    }

    @Test
    void compactsAncientTurnsAndKeepsRecentOnesWholeWhenOverThreshold() {
        StubAiAgentProvider provider = new StubAiAgentProvider();
        provider.enqueueFinal("Résumé : objectif X, fichiers a.txt et b.txt.");
        OffsetDateTime t0 = OffsetDateTime.now().minusHours(3);
        List<AtelierMessage> history = new ArrayList<>(List.of(
                message("USER", longText("demande 1"), t0),
                message("ASSISTANT", longText("réponse 1"), t0.plusMinutes(1)),
                message("USER", longText("demande 2"), t0.plusMinutes(2)),
                message("ASSISTANT", longText("réponse 2"), t0.plusMinutes(3))));
        stubHistory(history);

        AtelierCompactionService.CompactionOutcome outcome =
                service(provider).compactIfOversized(userId, workspace, null);

        assertThat(outcome.compacted()).isTrue();
        // 4 messages, on garde les 2 derniers entiers → 2 anciens résumés.
        assertThat(workspace.getChatThreadSummary())
                .isEqualTo("Résumé : objectif X, fichiers a.txt et b.txt.");
        // La frontière avance au premier message récent gardé (le 3e, index 2).
        assertThat(workspace.getChatThreadStartedAt()).isEqualTo(t0.plusMinutes(2));
        // Le fournisseur n'a vu QUE les tours anciens (aucun outil, appel dédié borné).
        AgentTurnRequest request = provider.lastRequest;
        assertThat(request.tools()).isEmpty();
        assertThat(request.system()).isEqualTo(AtelierCompactionService.SUMMARY_SYSTEM_PROMPT);
        verify(workspaceRepository).save(workspace);
    }

    @Test
    void doesNothingUnderThreshold() {
        StubAiAgentProvider provider = new StubAiAgentProvider();
        stubHistory(new ArrayList<>(List.of(
                message("USER", "petite demande", OffsetDateTime.now()),
                message("ASSISTANT", "petite réponse", OffsetDateTime.now()))));

        AtelierCompactionService.CompactionOutcome outcome =
                service(provider).compactIfOversized(userId, workspace, null);

        assertThat(outcome.compacted()).isFalse();
        assertThat(workspace.getChatThreadSummary()).isNull();
        assertThat(provider.lastRequest).isNull();
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void doesNothingWhenNothingIsOldEnoughToSummarize() {
        // Deux messages, on garde les 2 derniers : rien d'ancien à résumer, même au-dessus du seuil.
        StubAiAgentProvider provider = new StubAiAgentProvider();
        stubHistory(new ArrayList<>(List.of(
                message("USER", longText("demande"), OffsetDateTime.now()),
                message("ASSISTANT", longText("réponse"), OffsetDateTime.now()))));

        AtelierCompactionService.CompactionOutcome outcome =
                service(provider).compactIfOversized(userId, workspace, null);

        assertThat(outcome.compacted()).isFalse();
        assertThat(provider.lastRequest).isNull();
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void bestEffortLeavesFilUntouchedWhenSummaryCallFails() {
        AiAgentProvider provider = org.mockito.Mockito.mock(AiAgentProvider.class);
        when(provider.nextTurn(any())).thenThrow(new RuntimeException("fournisseur indisponible"));
        OffsetDateTime t0 = OffsetDateTime.now().minusHours(1);
        stubHistory(new ArrayList<>(List.of(
                message("USER", longText("d1"), t0),
                message("ASSISTANT", longText("r1"), t0.plusMinutes(1)),
                message("USER", longText("d2"), t0.plusMinutes(2)),
                message("ASSISTANT", longText("r2"), t0.plusMinutes(3)))));

        AtelierCompactionService.CompactionOutcome outcome =
                service(provider).compactIfOversized(userId, workspace, null);

        assertThat(outcome.compacted()).isFalse();
        assertThat(workspace.getChatThreadSummary()).isNull();
        assertThat(workspace.getChatThreadStartedAt()).isNull();
        verify(workspaceRepository, never()).save(any());
    }

    @Test
    void disabledDoesNothing() {
        StubAiAgentProvider provider = new StubAiAgentProvider();
        AtelierCompactionProperties disabled = new AtelierCompactionProperties(false, 100, 2);
        AtelierProperties atelier = new AtelierProperties(null, null, null, null, null, null, null,
                null, null, null, null, null, null, true);
        AtelierCompactionService service = new AtelierCompactionService(messageRepository,
                workspaceRepository, provider, disabled, atelier);

        AtelierCompactionService.CompactionOutcome outcome =
                service.compactIfOversized(userId, workspace, null);

        assertThat(outcome.compacted()).isFalse();
        assertThat(provider.lastRequest).isNull();
        verify(messageRepository, never()).findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(any(), any());
    }

    @Test
    void summaryIsIncrementalOverThePreviousSummary() {
        StubAiAgentProvider provider = new StubAiAgentProvider();
        provider.enqueueFinal("Nouveau résumé.");
        workspace.setChatThreadSummary("Résumé précédent important.");
        OffsetDateTime t0 = OffsetDateTime.now().minusHours(2);
        workspace.setChatThreadStartedAt(t0);
        when(messageRepository.findByWorkspaceIdAndUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                workspaceId, userId, t0)).thenReturn(new ArrayList<>(List.of(
                        message("USER", longText("d1"), t0),
                        message("ASSISTANT", longText("r1"), t0.plusMinutes(1)),
                        message("USER", longText("d2"), t0.plusMinutes(2)),
                        message("ASSISTANT", longText("r2"), t0.plusMinutes(3)))));

        AtelierCompactionService.CompactionOutcome outcome =
                service(provider).compactIfOversized(userId, workspace, null);

        assertThat(outcome.compacted()).isTrue();
        assertThat(workspace.getChatThreadSummary()).isEqualTo("Nouveau résumé.");
        // Le texte soumis au résumé porte l'ancien résumé (compaction incrémentale).
        String submitted = provider.lastRequest.messages().get(0).content().get(0).toString();
        assertThat(submitted).contains("Résumé précédent important.");
    }

    @Test
    void summaryPrefixInjectsMarkerWhenPresent() {
        assertThat(AtelierCompactionService.summaryPrefix(workspace)).isNull();
        workspace.setChatThreadSummary("un résumé");
        var prefix = AtelierCompactionService.summaryPrefix(workspace);
        assertThat(prefix).isNotNull();
        assertThat(prefix.content().get(0).toString()).contains(AtelierCompactionService.SUMMARY_MARKER);
    }

    // ------------------------------------------- F-119 / SF-119-03 : digest structuré des outils

    @Test
    void theSummaryIncludesAStructuredToolDigestNotJustText() {
        // La compaction résumait le texte seul et jetait les sorties d'outils → l'agent raisonnait
        // sur un digest sans preuves. Désormais, un digest structuré (fichiers lus, commandes et leur
        // issue) accompagne le texte de chaque tour de l'agent.
        String trace = new AtelierToolTrace(List.of(
                new AtelierToolTrace.Step("je regarde", List.of(
                        new AtelierToolTrace.Call("c1", "read_file",
                                input("path", "src/App.java"), "     1→package app;", false),
                        new AtelierToolTrace.Call("c2", "bash",
                                input("command", "mvn -q test"),
                                "$ mvn -q test\nBUILD FAILURE\n[code de sortie: 1]", false)))))
                .toJson();
        AtelierMessage assistant = AtelierMessage.builder().id(UUID.randomUUID())
                .workspaceId(workspaceId).userId(userId).role("ASSISTANT")
                .content("J'ai lancé les tests.").toolTrace(trace).build();

        String rendered = AtelierCompactionService.renderForSummary(null, List.of(assistant));

        assertThat(rendered).contains("ASSISTANT : J'ai lancé les tests.");
        assertThat(rendered).contains("· read_file src/App.java");
        assertThat(rendered).contains("· bash « mvn -q test »");
        // L'issue de la commande survit (code de sortie), pas seulement le texte.
        assertThat(rendered).contains("[code de sortie: 1]");
    }

    @Test
    void anAssistantTurnWithOnlyToolsStillCarriesItsDigest() {
        // Un tour d'agent sans texte mais avec des outils n'est plus écarté : sa trajectoire est
        // justement ce qui doit survivre au résumé.
        String trace = new AtelierToolTrace(List.of(
                new AtelierToolTrace.Step("", List.of(
                        new AtelierToolTrace.Call("c1", "edit_file",
                                input("path", "notes.txt"), "Fichier modifié : notes.txt (1 remplacement)",
                                false)))))
                .toJson();
        AtelierMessage assistant = AtelierMessage.builder().id(UUID.randomUUID())
                .workspaceId(workspaceId).userId(userId).role("ASSISTANT")
                .content("").toolTrace(trace).build();

        String rendered = AtelierCompactionService.renderForSummary(null, List.of(assistant));

        assertThat(rendered).contains("· edit_file notes.txt");
    }

    @Test
    void toolDigestIsEmptyForNoTrace() {
        assertThat(AtelierCompactionService.toolDigest(null)).isEmpty();
        assertThat(AtelierCompactionService.toolDigest("{tronqué")).isEmpty();
    }

    // ------------------------------------------- F-119 / SF-119-03 : parité de l'estimateur de seuil

    @Test
    void estimateCountsReplayedToolTracesNotJustText() {
        // Les traces d'outils des derniers tours repartent AUSSI au fournisseur : l'estimateur doit
        // les compter, sinon un fil au texte court mais aux sorties d'outils volumineuses franchit le
        // seuil réel sans jamais déclencher la compaction (régression de débordement).
        String bigTrace = "x".repeat(8_000);
        List<AtelierMessage> history = List.of(
                message("USER", "demande", OffsetDateTime.now()),
                assistantWithTrace("réponse", bigTrace, OffsetDateTime.now().plusMinutes(1)));

        long textOnly = AtelierCompactionService.estimateReplayTokens(null, history);
        long withTraces = AtelierCompactionService.estimateReplayTokens(null, history, 12);

        assertThat(withTraces).isGreaterThan(textOnly);
        // Le texte seul reste sous le seuil du test (100 tokens) ; les traces le franchissent.
        assertThat(textOnly).isLessThan(100);
        assertThat(withTraces).isGreaterThan(100);
    }

    @Test
    void voluminousToolTracesTriggerCompactionEvenWhenTextIsShort() {
        // Bout en bout : un fil au texte court mais aux traces volumineuses DÉCLENCHE la compaction —
        // c'est exactement le cas que l'estimateur text-only ratait.
        StubAiAgentProvider provider = new StubAiAgentProvider();
        provider.enqueueFinal("Résumé compact.");
        OffsetDateTime t0 = OffsetDateTime.now().minusHours(1);
        String bigTrace = "x".repeat(8_000);
        List<AtelierMessage> history = new ArrayList<>(List.of(
                message("USER", "d1", t0),
                assistantWithTrace("r1", bigTrace, t0.plusMinutes(1)),
                message("USER", "d2", t0.plusMinutes(2)),
                assistantWithTrace("r2", bigTrace, t0.plusMinutes(3))));
        stubHistory(history);

        AtelierCompactionService.CompactionOutcome outcome =
                service(provider).compactIfOversized(userId, workspace, null);

        assertThat(outcome.compacted()).isTrue();
    }

    // ------------------------------------- F-121 / SF-121-09 : gabarit sectionné du résumé

    @Test
    void theSummaryInstructionImposesTheFiveSectionsInOrder() {
        // Avant : « produis un résumé factuel et compact » → prose libre, forme différente à chaque
        // compaction. Désormais le gabarit est imposé, et son ordre fait partie de la consigne.
        String prompt = AtelierCompactionService.SUMMARY_SYSTEM_PROMPT;

        int cursor = 0;
        for (String section : AtelierCompactionService.SUMMARY_SECTIONS) {
            int at = prompt.indexOf(section, cursor);
            assertThat(at).as("section « %s » présente et après la précédente", section)
                    .isGreaterThanOrEqualTo(0);
            cursor = at + section.length();
        }
        assertThat(AtelierCompactionService.SUMMARY_SECTIONS).containsExactly(
                "## Objectif", "## Fichiers", "## Décisions et faits établis", "## État courant",
                "## Prochaines étapes");
    }

    @Test
    void theSummaryInstructionKeepsEmptySectionsAndTheExistingGuardrails() {
        String prompt = AtelierCompactionService.SUMMARY_SYSTEM_PROMPT;

        // Une section vide est conservée (structure constante) plutôt que supprimée.
        assertThat(prompt).contains("Garde TOUJOURS les cinq sections").contains("« — »");
        // Les garde-fous F-117 / SF-119-03 survivent mot pour mot à la réécriture.
        assertThat(prompt).contains("pas de préambule, pas de conclusion");
        assertThat(prompt).contains("N'INVENTE RIEN");
        assertThat(prompt).contains("« · »");
        assertThat(prompt).contains("LEUR ISSUE");
    }

    @Test
    void theMergeInstructionIsAddedOnlyWhenAPreviousSummaryExists() {
        AtelierMessage turn = message("USER", "demande", OffsetDateTime.now());

        String withoutPrevious = AtelierCompactionService.renderForSummary(null, List.of(turn));
        String withPrevious = AtelierCompactionService.renderForSummary(
                "## Objectif\nLivrer X.\n## Prochaines étapes\nFinir Y.", List.of(turn));

        assertThat(withoutPrevious).doesNotContain(AtelierCompactionService.MERGE_INSTRUCTION);
        // La consigne de fusion précède l'ancien résumé : elle dépend de l'entrée, donc elle vit dans
        // le message et non dans la consigne système (préfixe stable).
        assertThat(withPrevious).contains(AtelierCompactionService.MERGE_INSTRUCTION);
        assertThat(withPrevious.indexOf(AtelierCompactionService.MERGE_INSTRUCTION))
                .isLessThan(withPrevious.indexOf("Résumé précédent :"));
        assertThat(withPrevious).contains("Livrer X.");
    }

    @Test
    void looksSectionedRecognisesTheTemplateAndOnlyInTheRightOrder() {
        String wellFormed = "## Objectif\nX\n## Fichiers\n—\n## Décisions et faits établis\n—\n"
                + "## État courant\n—\n## Prochaines étapes\n—";
        String shuffled = "## Fichiers\n—\n## Objectif\nX\n## Décisions et faits établis\n—\n"
                + "## État courant\n—\n## Prochaines étapes\n—";

        assertThat(AtelierCompactionService.looksSectioned(wellFormed)).isTrue();
        assertThat(AtelierCompactionService.looksSectioned(shuffled)).isFalse();
        assertThat(AtelierCompactionService.looksSectioned("de la prose libre")).isFalse();
        assertThat(AtelierCompactionService.looksSectioned(null)).isFalse();
        assertThat(AtelierCompactionService.looksSectioned("  ")).isFalse();
    }

    @Test
    void anOffTemplateSummaryIsStillWritten() {
        // D3 : la conformité est observée, jamais exigée — refuser un résumé mal formé reviendrait à
        // ne pas compacter un fil qui déborde.
        StubAiAgentProvider provider = new StubAiAgentProvider();
        provider.enqueueFinal("Résumé en prose libre, sans le moindre titre.");
        OffsetDateTime t0 = OffsetDateTime.now().minusHours(1);
        stubHistory(new ArrayList<>(List.of(
                message("USER", longText("d1"), t0),
                message("ASSISTANT", longText("r1"), t0.plusMinutes(1)),
                message("USER", longText("d2"), t0.plusMinutes(2)),
                message("ASSISTANT", longText("r2"), t0.plusMinutes(3)))));

        AtelierCompactionService.CompactionOutcome outcome =
                service(provider).compactIfOversized(userId, workspace, null);

        assertThat(outcome.compacted()).isTrue();
        assertThat(workspace.getChatThreadSummary())
                .isEqualTo("Résumé en prose libre, sans le moindre titre.");
        verify(workspaceRepository).save(workspace);
    }

    private AtelierMessage assistantWithTrace(String content, String toolTrace, OffsetDateTime at) {
        return AtelierMessage.builder().id(UUID.randomUUID()).workspaceId(workspaceId).userId(userId)
                .role("ASSISTANT").content(content).toolTrace(toolTrace).createdAt(at).build();
    }

    private static com.fasterxml.jackson.databind.node.ObjectNode input(String key, String value) {
        com.fasterxml.jackson.databind.node.ObjectNode node =
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        node.put(key, value);
        return node;
    }
}
