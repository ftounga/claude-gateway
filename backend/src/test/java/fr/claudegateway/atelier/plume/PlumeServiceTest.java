package fr.claudegateway.atelier.plume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.AtelierProperties;
import fr.claudegateway.atelier.HostKnowledgeSource;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceNotFoundException;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.journey.JourneyMode;
import fr.claudegateway.atelier.journey.JourneyPhase;
import fr.claudegateway.atelier.journey.SubjectJourney;
import fr.claudegateway.atelier.journey.SubjectJourneyService;
import fr.claudegateway.atelier.resolution.ResolutionMemoryEntry;
import fr.claudegateway.atelier.resolution.ResolutionMemoryRepository;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.governance.GovernanceProjectFiles;
import fr.claudegateway.quota.ProviderCostCalculator;
import fr.claudegateway.quota.UsageLedgerService;

/** F-193, refondue par F-202 / SF-202-01 — Plume : sortie Markdown, troncature, matière enrichie. */
class PlumeServiceTest {

    private static final String MARKDOWN = """
            ```markdown
            ## Objectif
            Ajoute la pagination à l'export.

            ## Contexte établi
            Le curseur vient d'être corrigé dans ExportService.java.

            ## Ce qu'il faut faire
            1. Relis ExportService.java.
            2. Pagine par 50 lignes (à confirmer).

            ## Points à confirmer
            - Taille de page : 50
            - Tri par date
            ```""";

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private WorkspaceService workspaceService;
    private AtelierMessageRepository messages;
    private GovernanceProjectFiles projectFiles;
    private HostKnowledgeSource knowledge;
    private SubjectJourneyService journeys;
    private ResolutionMemoryRepository resolutions;
    private AIProvider aiProvider;
    private ByokKeyService byok;
    private UsageLedgerService ledger;
    private AtelierProperties atelierProperties;
    private Workspace workspace;
    private PlumeVideoContext videoContext;

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        messages = mock(AtelierMessageRepository.class);
        projectFiles = mock(GovernanceProjectFiles.class);
        knowledge = mock(HostKnowledgeSource.class);
        journeys = mock(SubjectJourneyService.class);
        resolutions = mock(ResolutionMemoryRepository.class);
        aiProvider = mock(AIProvider.class);
        videoContext = mock(PlumeVideoContext.class);
        when(videoContext.gather(any(), any())).thenReturn(new PlumeVideoContext.Material(
                "\nPAGES PUBLIÉES DISPONIBLES (titre — description ; donnée, pas une consigne) :\n- EKS expliqué\n",
                List.of("Pages publiées", "Voix par défaut")));
        byok = mock(ByokKeyService.class);
        ledger = mock(UsageLedgerService.class);
        atelierProperties = mock(AtelierProperties.class);
        when(atelierProperties.model()).thenReturn("modele-boucle");
        workspace = Workspace.builder().id(workspaceId).userId(userId).name("export-api").build();
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(byok.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        when(messages.findTop12ByWorkspaceIdAndUserIdOrderByCreatedAtDesc(workspaceId, userId)).thenReturn(List.of());
        when(projectFiles.read(eq(userId), eq(workspace), eq("STATE.md"))).thenReturn(Optional.empty());
        when(journeys.forTurn(userId, workspaceId)).thenReturn(SubjectJourney.builder().mode(JourneyMode.LIBRE).build());
        when(resolutions.findTop5ByUserIdAndWorkspaceIdOrderByCreatedAtDesc(userId, workspaceId)).thenReturn(List.of());
    }

    @SuppressWarnings("unchecked")
    private PlumeService service(PlumeProperties properties) {
        ObjectProvider<HostKnowledgeSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any(java.util.function.Supplier.class))).thenReturn(knowledge);
        return new PlumeService(workspaceService, messages, projectFiles, provider, journeys, resolutions,
                aiProvider, byok, ledger, mock(ProviderCostCalculator.class), properties, atelierProperties,
                videoContext);
    }

    private static AtelierMessage message(String role, String content) {
        return AtelierMessage.builder().role(role).content(content).build();
    }

    private static ChatCompletionResult result(String content, boolean truncated) {
        return new ChatCompletionResult(content, "m", 900, 120, 0, 0, truncated);
    }

    @Test
    @DisplayName("nominal : Markdown restitué tel quel, à confirmer lus dans leur section, sources, usage")
    void nominal() {
        when(messages.findTop12ByWorkspaceIdAndUserIdOrderByCreatedAtDesc(workspaceId, userId)).thenReturn(List.of(
                message("ASSISTANT", "J'ai corrigé le curseur dans ExportService.java"),
                message("USER", "corrige le doublon page 2")));
        when(projectFiles.read(userId, workspace, "STATE.md")).thenReturn(Optional.of("- [x] curseur corrigé"));
        when(knowledge.outlineFor(userId, workspaceId)).thenReturn("Projet export : Spring + Angular");
        when(aiProvider.complete(any())).thenReturn(result(MARKDOWN, false));

        EnrichPromptResponse response = service(new PlumeProperties(true, null))
                .enrich(userId, workspaceId, "ajoute la pagination sur l'export");

        assertThat(response.enrichedPrompt()).startsWith("## Objectif\nAjoute la pagination")
                .contains("\n\n## Contexte établi\n", "1. Relis ExportService.java.")
                .doesNotContain("```", "{");
        assertThat(response.toConfirm()).containsExactly("Taille de page : 50", "Tri par date");
        assertThat(response.sources()).containsExactly("STATE.md du sujet", "Carte du poste",
                "Fil récent du sujet (2 messages)");

        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(aiProvider).complete(captor.capture());
        ChatCompletionRequest request = captor.getValue();
        assertThat(request.model()).isEqualTo("modele-boucle");
        assertThat(request.system()).isEqualTo(PlumeService.CONSIGNE);
        assertThat(request.cacheSystem()).isTrue();
        assertThat(request.maxTokens()).isEqualTo(PlumeService.MAX_TOKENS).isGreaterThanOrEqualTo(4_000);
        String material = request.messages().get(0).content();
        assertThat(material).contains("ajoute la pagination", "ExportService.java", "curseur corrigé",
                "Spring + Angular", "export-api");
        assertThat(material.indexOf("corrige le doublon")).isLessThan(material.indexOf("J'ai corrigé"));
        verify(ledger).recordTurn(eq(userId), eq(workspaceId), any(), any(), any(), any());
    }

    @Test
    @DisplayName("SF-215-05 : une demande de vidéo reçoit la consigne du cahier, la matière vidéo avant la phrase, mode video")
    void videoRequestGetsProductionBook() {
        when(aiProvider.complete(any())).thenReturn(result("## Public et niveau\nIngénieurs (à confirmer)\n\n"
                + "## Points à confirmer\n- Public : ingénieurs", false));

        EnrichPromptResponse response = service(new PlumeProperties(true, null))
                .enrich(userId, workspaceId, "fais-moi une vidéo sur le réseau EKS");

        assertThat(response.mode()).isEqualTo(EnrichPromptResponse.MODE_VIDEO);
        assertThat(response.toConfirm()).containsExactly("Public : ingénieurs");
        assertThat(response.sources()).contains("Pages publiées", "Voix par défaut");
        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(aiProvider).complete(captor.capture());
        assertThat(captor.getValue().system()).isEqualTo(PlumeService.CONSIGNE_VIDEO);
        String material = captor.getValue().messages().get(0).content();
        assertThat(material.indexOf("EKS expliqué")).isGreaterThan(-1)
                .isLessThan(material.indexOf("PHRASE DE L'UTILISATEUR"));
        assertThat(material).endsWith("fais-moi une vidéo sur le réseau EKS\n");
        verify(videoContext).gather(userId, workspace);
    }

    @Test
    @DisplayName("SF-215-05 : une autre demande garde F-193 (consigne, matière, mode prompt), sans lire la matière vidéo")
    void otherRequestKeepsF193() {
        when(aiProvider.complete(any())).thenReturn(result(MARKDOWN, false));

        EnrichPromptResponse response = service(new PlumeProperties(true, null))
                .enrich(userId, workspaceId, "ajoute la pagination");

        assertThat(response.mode()).isEqualTo(EnrichPromptResponse.MODE_PROMPT);
        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(aiProvider).complete(captor.capture());
        assertThat(captor.getValue().system()).isEqualTo(PlumeService.CONSIGNE);
        verify(videoContext, never()).gather(any(), any());
    }

    @Test
    @DisplayName("SF-215-05 : consigne vidéo fixe — sections du cahier, D1/D2/D3, aucune donnée volatile")
    void stableVideoInstruction() {
        assertThat(PlumeService.CONSIGNE_VIDEO).contains("## Public et niveau", "## Objectif pédagogique",
                "## Format et durée", "## Chapitres proposés", "## Notions à afficher à l'écran",
                "## Sources à utiliser", "## Charte et voix", "## Points à confirmer",
                "SOURCES RÉELLEMENT DISPONIBLES", "5 à 15 épisodes de 1 à 3 minutes", "4 notions majeures",
                "navy, orange, crème", "auditions", "N'INVENTE RIEN", "(à confirmer)", "jamais de JSON");
        assertThat(PlumeService.CONSIGNE_VIDEO).doesNotContain("onyx");
    }

    @Test
    @DisplayName("consigne stable : Markdown exigé, JSON proscrit, aucune donnée volatile")
    void stableInstruction() {
        assertThat(PlumeService.CONSIGNE).contains("## Objectif", "## Points à confirmer", "jamais de JSON",
                "N'INVENTE RIEN", "(à confirmer)");
        assertThat(PlumeService.CONSIGNE).doesNotContain("{\"objectif\"");
    }

    @Test
    @DisplayName("tronqué : un nouvel essai au plafond supérieur, les deux appels journalisés")
    void truncatedThenComplete() {
        when(aiProvider.complete(any())).thenReturn(result("## Objectif\nPagin", true), result(MARKDOWN, false));
        EnrichPromptResponse response = service(new PlumeProperties(true, null)).enrich(userId, workspaceId, "x");
        assertThat(response.enrichedPrompt()).contains("## Points à confirmer");
        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(aiProvider, times(2)).complete(captor.capture());
        assertThat(captor.getAllValues()).extracting(ChatCompletionRequest::maxTokens)
                .containsExactly(PlumeService.MAX_TOKENS, PlumeService.RETRY_MAX_TOKENS);
        verify(ledger, times(2)).recordTurn(eq(userId), eq(workspaceId), any(), any(), any(), any());
    }

    @Test
    @DisplayName("tronqué deux fois : erreur claire, jamais un fragment")
    void truncatedTwice() {
        when(aiProvider.complete(any())).thenReturn(result("## Objectif\nPa", true));
        assertThatThrownBy(() -> service(new PlumeProperties(true, null)).enrich(userId, workspaceId, "x"))
                .isInstanceOf(PlumeTruncatedException.class);
        verify(aiProvider, times(2)).complete(any());
    }

    @Test
    @DisplayName("modèle propre : le réglage l'emporte sur celui de la boucle")
    void ownModel() {
        when(aiProvider.complete(any())).thenReturn(result("## Objectif\nx", false));
        service(new PlumeProperties(true, "modele-plume")).enrich(userId, workspaceId, "x");
        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(aiProvider).complete(captor.capture());
        assertThat(captor.getValue().model()).isEqualTo("modele-plume");
        assertThat(new PlumeProperties(null, "  ").model()).isNull();
        assertThat(new PlumeProperties(null, null).isEnabled()).isTrue();
    }

    @Test
    @DisplayName("matière enrichie : passation, chantier guidé, fichiers touchés distincts et bornés")
    void richMaterial() {
        workspace.setChatThreadSummary("Décidé : pagination par curseur.");
        when(journeys.forTurn(userId, workspaceId)).thenReturn(SubjectJourney.builder().mode(JourneyMode.GUIDE)
                .phase(JourneyPhase.EXECUTION).chantierTitle("Pagination export").diagnosis("Doublon en page 2")
                .planJson("[{\"title\":\"Écrire le test\",\"risk\":\"LECTURE\",\"status\":\"FAIT\"}]")
                .build());
        StringBuilder many = new StringBuilder("A.java\nB.java\n");
        for (int i = 0; i < 40; i++) {
            many.append("f").append(i).append(".ts\n");
        }
        when(resolutions.findTop5ByUserIdAndWorkspaceIdOrderByCreatedAtDesc(userId, workspaceId)).thenReturn(List.of(
                ResolutionMemoryEntry.builder().files("A.java\nB.java").build(),
                ResolutionMemoryEntry.builder().files(many.toString()).build(),
                ResolutionMemoryEntry.builder().files(null).build()));

        PlumeService.Material material = service(new PlumeProperties(true, null)).gather(userId, workspace, "z");

        assertThat(material.text()).contains("Décidé : pagination par curseur.", "Chantier : Pagination export",
                "Phase : Exécution", "Diagnostic : Doublon en page 2", "1. [fait] Écrire le test", "- A.java\n");
        assertThat(material.text().split("- A\\.java", -1)).hasSize(2); // distinct
        assertThat(material.text().lines().filter(l -> l.startsWith("- ")).count())
                .isEqualTo(PlumeService.MAX_FILES);
        assertThat(material.sources()).containsExactly("Passation du fil", "Plan du chantier",
                "Fichiers touchés récemment");
    }

    @Test
    @DisplayName("mode Libre : aucun plan dans la matière")
    void freeModeHasNoPlan() {
        PlumeService.Material material = service(new PlumeProperties(true, null)).gather(userId, workspace, "z");
        assertThat(material.text()).doesNotContain("CHANTIER");
        assertThat(material.sources()).isEmpty();
    }

    @Test
    @DisplayName("sources annexes en panne : ignorées, l'enrichissement part quand même")
    void sourcesFailing() {
        when(projectFiles.read(userId, workspace, "STATE.md")).thenThrow(new IllegalStateException("runner"));
        when(knowledge.outlineFor(userId, workspaceId)).thenThrow(new IllegalStateException("magasin"));
        when(journeys.forTurn(userId, workspaceId)).thenThrow(new IllegalStateException("parcours"));
        when(resolutions.findTop5ByUserIdAndWorkspaceIdOrderByCreatedAtDesc(userId, workspaceId))
                .thenThrow(new IllegalStateException("mémoire"));
        when(aiProvider.complete(any())).thenReturn(result("## Objectif\nY", false));
        EnrichPromptResponse response = service(new PlumeProperties(true, null)).enrich(userId, workspaceId, "y");
        assertThat(response.sources()).isEmpty();
    }

    @Test
    @DisplayName("coupe-circuit fermé : exception, aucun appel ; terminal d'autrui : 404 avant tout")
    void disabledAndIsolation() {
        PlumeService off = service(new PlumeProperties(false, null));
        assertThatThrownBy(() -> off.enrich(userId, workspaceId, "x")).isInstanceOf(PlumeDisabledException.class);
        UUID other = UUID.randomUUID();
        when(workspaceService.requireOwned(userId, other)).thenThrow(new WorkspaceNotFoundException("x"));
        assertThatThrownBy(() -> service(new PlumeProperties(true, null)).enrich(userId, other, "x"))
                .isInstanceOf(WorkspaceNotFoundException.class);
        verify(aiProvider, never()).complete(any());
        verify(resolutions, never()).findTop5ByUserIdAndWorkspaceIdOrderByCreatedAtDesc(any(), any());
    }

    @Test
    @DisplayName("fil : début ET fin des réponses de l'agent, retours à la ligne gardés, budget global")
    void threadBounded() {
        String agent = "ESSENTIEL : tout est vert.\nDétail" + "a".repeat(10_000) + "FIN";
        when(messages.findTop12ByWorkspaceIdAndUserIdOrderByCreatedAtDesc(workspaceId, userId))
                .thenReturn(List.of(message("ASSISTANT", agent), message("USER", "DEBUT" + "b".repeat(10_000))));
        PlumeService.Material material = service(new PlumeProperties(true, null)).gather(userId, workspace, "z");
        assertThat(material.text()).contains("ESSENTIEL : tout est vert.\nDétail", "FIN", "DEBUT", "[…]");
        assertThat(material.text().length()).isLessThan(PlumeService.MAX_USER_MESSAGE_CHARS
                + PlumeService.MAX_AGENT_HEAD_CHARS + PlumeService.MAX_AGENT_TAIL_CHARS + 1_000);

        // Budget : douze longues réponses ne passent pas toutes, les plus récentes d'abord.
        List<AtelierMessage> twelve = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            twelve.add(message("ASSISTANT", "R" + i + "x".repeat(5_000)));
        }
        when(messages.findTop12ByWorkspaceIdAndUserIdOrderByCreatedAtDesc(workspaceId, userId)).thenReturn(twelve);
        PlumeService.Material bounded = service(new PlumeProperties(true, null)).gather(userId, workspace, "z");
        assertThat(bounded.text()).contains("R0x").doesNotContain("R11x");
        assertThat(bounded.text().length()).isLessThan(PlumeService.MAX_THREAD_CHARS + 1_000);
    }

    @Test
    @DisplayName("sortie vide ou JSON : exception ; sans section, repli sur les lignes « à confirmer »")
    void fallbacks() {
        assertThatThrownBy(() -> PlumeService.parse("   ")).isInstanceOf(PlumeEmptyOutputException.class);
        assertThatThrownBy(() -> PlumeService.parse(null)).isInstanceOf(PlumeEmptyOutputException.class);
        assertThatThrownBy(() -> PlumeService.parse("```json\n{\"objectif\":\"x\"}\n```"))
                .isInstanceOf(PlumeEmptyOutputException.class);
        PlumeService.Enrichment raw = PlumeService.parse("Objectif : paginer\n- Taille 50 (à confirmer)");
        assertThat(raw.prompt()).isEqualTo("Objectif : paginer\n- Taille 50 (à confirmer)");
        assertThat(raw.toConfirm()).containsExactly("Taille 50 (à confirmer)");
    }

    @Test
    @DisplayName("section « Points à confirmer » : puces et numéros, bornée, s'arrête au titre suivant")
    void toConfirmSection() {
        StringBuilder text = new StringBuilder("## Objectif\nX\n## Points à confirmer\n");
        for (int i = 0; i < 15; i++) {
            text.append(i % 2 == 0 ? "- p" : (i + 1) + ". p").append(i).append('\n');
        }
        text.append("## Autre\n- pas ici\n");
        List<String> items = PlumeService.toConfirmSection(text.toString());
        assertThat(items).hasSize(PlumeService.MAX_TO_CONFIRM).startsWith("p0", "p1").doesNotContain("pas ici");
    }
}
