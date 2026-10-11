package fr.claudegateway.atelier.plume;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.governance.GovernanceProjectFiles;
import fr.claudegateway.quota.ProviderCostCalculator;
import fr.claudegateway.quota.UsageLedgerService;

/** F-193 / SF-193-01 — Plume : matière bornée, composition, repli, coupe-circuit. */
class PlumeServiceTest {

    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();

    private WorkspaceService workspaceService;
    private AtelierMessageRepository messages;
    private GovernanceProjectFiles projectFiles;
    private HostKnowledgeSource knowledge;
    private AIProvider aiProvider;
    private ByokKeyService byok;
    private UsageLedgerService ledger;
    private AtelierProperties atelierProperties;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        messages = mock(AtelierMessageRepository.class);
        projectFiles = mock(GovernanceProjectFiles.class);
        knowledge = mock(HostKnowledgeSource.class);
        aiProvider = mock(AIProvider.class);
        byok = mock(ByokKeyService.class);
        ledger = mock(UsageLedgerService.class);
        atelierProperties = mock(AtelierProperties.class);
        when(atelierProperties.model()).thenReturn("modele-boucle");
        workspace = Workspace.builder().id(workspaceId).userId(userId).name("export-api").build();
        when(workspaceService.requireOwned(userId, workspaceId)).thenReturn(workspace);
        when(byok.resolveActiveApiKey(userId)).thenReturn(Optional.empty());
        when(messages.findTop6ByWorkspaceIdAndUserIdOrderByCreatedAtDesc(workspaceId, userId)).thenReturn(List.of());
        when(projectFiles.read(eq(userId), eq(workspace), eq("STATE.md"))).thenReturn(Optional.empty());
    }

    @SuppressWarnings("unchecked")
    private PlumeService service(PlumeProperties properties) {
        ObjectProvider<HostKnowledgeSource> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any(java.util.function.Supplier.class))).thenReturn(knowledge);
        return new PlumeService(workspaceService, messages, projectFiles, provider, aiProvider, byok, ledger,
                mock(ProviderCostCalculator.class), properties, atelierProperties);
    }

    private static AtelierMessage message(String role, String content) {
        return AtelierMessage.builder().role(role).content(content).build();
    }

    @Test
    @DisplayName("nominal : matière complète, sections composées, à confirmer, sources, journal d'usage")
    void nominal() {
        when(messages.findTop6ByWorkspaceIdAndUserIdOrderByCreatedAtDesc(workspaceId, userId)).thenReturn(List.of(
                message("ASSISTANT", "J'ai corrigé le curseur dans ExportService.java"),
                message("USER", "corrige le doublon page 2")));
        when(projectFiles.read(userId, workspace, "STATE.md")).thenReturn(Optional.of("- [x] curseur corrigé"));
        when(knowledge.outlineFor(userId, workspaceId)).thenReturn("Projet export : Spring + Angular");
        when(aiProvider.complete(any())).thenReturn(new ChatCompletionResult("""
                ```json
                {"objectif":"Paginer l'export","contexte":["Le curseur vient d'être corrigé"],
                 "fichiers":["ExportService.java"],"contraintes":["Taille de page : 50 (à confirmer)"],
                 "critere_de_fin":"Tests verts","a_confirmer":["Taille de page : 50"]}
                ```""", "modele-boucle", 900, 120, 0, 0));

        EnrichPromptResponse response = service(new PlumeProperties(true, null))
                .enrich(userId, workspaceId, "ajoute la pagination sur l'export");

        assertThat(response.enrichedPrompt()).isEqualTo("""
                Objectif : Paginer l'export
                Contexte :
                - Le curseur vient d'être corrigé
                Fichiers concernés :
                - ExportService.java
                Contraintes :
                - Taille de page : 50 (à confirmer)
                Critère de fin : Tests verts""");
        assertThat(response.toConfirm()).containsExactly("Taille de page : 50");
        assertThat(response.sources()).containsExactly("Fil récent du sujet (2 messages)", "STATE.md du sujet",
                "Carte du poste");

        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(aiProvider).complete(captor.capture());
        ChatCompletionRequest request = captor.getValue();
        assertThat(request.model()).isEqualTo("modele-boucle");
        assertThat(request.system()).isEqualTo(PlumeService.CONSIGNE);
        assertThat(request.maxTokens()).isEqualTo(PlumeService.MAX_TOKENS);
        String material = request.messages().get(0).content();
        assertThat(material).contains("ajoute la pagination", "ExportService.java", "curseur corrigé",
                "Spring + Angular", "export-api");
        // Ordre chronologique : la demande avant la réponse.
        assertThat(material.indexOf("corrige le doublon")).isLessThan(material.indexOf("J'ai corrigé"));
        verify(ledger).recordTurn(eq(userId), eq(workspaceId), any(), any(), any(), any());
    }

    @Test
    @DisplayName("modèle propre : le réglage l'emporte sur celui de la boucle")
    void ownModel() {
        when(aiProvider.complete(any())).thenReturn(new ChatCompletionResult("{\"objectif\":\"x\"}", "m", 1, 1, 0, 0));
        service(new PlumeProperties(true, "modele-plume")).enrich(userId, workspaceId, "x");
        ArgumentCaptor<ChatCompletionRequest> captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(aiProvider).complete(captor.capture());
        assertThat(captor.getValue().model()).isEqualTo("modele-plume");
        assertThat(new PlumeProperties(null, "  ").model()).isNull();
        assertThat(new PlumeProperties(null, null).isEnabled()).isTrue();
    }

    @Test
    @DisplayName("sans fil, sans STATE.md, sans carte : seule la phrase part, aucune source")
    void bareMaterial() {
        when(aiProvider.complete(any())).thenReturn(new ChatCompletionResult("{\"objectif\":\"Faire X\"}", "m", 1, 1, 0, 0));
        EnrichPromptResponse response = service(new PlumeProperties(true, null)).enrich(userId, workspaceId, "fais X");
        assertThat(response.sources()).isEmpty();
        assertThat(response.enrichedPrompt()).isEqualTo("Objectif : Faire X");
        assertThat(response.toConfirm()).isEmpty();
    }

    @Test
    @DisplayName("STATE.md et carte en panne : ignorés, l'enrichissement part quand même")
    void sourcesFailing() {
        when(projectFiles.read(userId, workspace, "STATE.md")).thenThrow(new IllegalStateException("runner"));
        when(knowledge.outlineFor(userId, workspaceId)).thenThrow(new IllegalStateException("magasin"));
        when(aiProvider.complete(any())).thenReturn(new ChatCompletionResult("{\"objectif\":\"Y\"}", "m", 1, 1, 0, 0));
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
    }

    @Test
    @DisplayName("matière bornée : messages, STATE.md, titre tronqués")
    void bounded() {
        String huge = "a".repeat(10_000);
        when(messages.findTop6ByWorkspaceIdAndUserIdOrderByCreatedAtDesc(workspaceId, userId))
                .thenReturn(List.of(message("ASSISTANT", huge + "FIN"), message("USER", "DEBUT" + huge)));
        when(projectFiles.read(userId, workspace, "STATE.md")).thenReturn(Optional.of(huge));
        PlumeService.Material material = service(new PlumeProperties(true, null)).gather(userId, workspace, "z");
        assertThat(material.text()).contains("FIN", "DEBUT");
        assertThat(material.text().length()).isLessThan(1_500 * 2 + 4_000 + 1_000);
        assertThat(material.sources()).containsExactly("Fil récent du sujet (2 messages)", "STATE.md du sujet");
    }

    @Test
    @DisplayName("sortie vide : exception ; texte non JSON : repli brut et lignes « à confirmer »")
    void fallbacks() {
        assertThatThrownBy(() -> PlumeService.parse("   ", "x")).isInstanceOf(PlumeEmptyOutputException.class);
        assertThatThrownBy(() -> PlumeService.parse(null, "x")).isInstanceOf(PlumeEmptyOutputException.class);
        PlumeService.Enrichment raw = PlumeService.parse("Objectif : paginer\n- Taille 50 (à confirmer)", "x");
        assertThat(raw.prompt()).isEqualTo("Objectif : paginer\n- Taille 50 (à confirmer)");
        assertThat(raw.toConfirm()).containsExactly("Taille 50 (à confirmer)");
    }

    @Test
    @DisplayName("JSON : objectif absent ⇒ texte d'origine ; à confirmer déduit des lignes ; bornes des listes")
    void jsonEdges() {
        PlumeService.Enrichment e = PlumeService.parse(
                "Voici : {\"contraintes\":[\"Délai 2 j (à confirmer)\",\"\"],\"fichiers\":\"A.java\"} fin",
                "  ma phrase ");
        assertThat(e.prompt()).isEqualTo("""
                Objectif : ma phrase
                Fichiers concernés :
                - A.java
                Contraintes :
                - Délai 2 j (à confirmer)""");
        assertThat(e.toConfirm()).containsExactly("Délai 2 j (à confirmer)");

        StringBuilder many = new StringBuilder("{\"contexte\":[");
        for (int i = 0; i < 20; i++) {
            many.append(i == 0 ? "" : ",").append("\"c").append(i).append("\\nsuite\"");
        }
        many.append("]}");
        PlumeService.Enrichment bounded = PlumeService.parse(many.toString(), "o");
        assertThat(bounded.prompt().lines().filter(l -> l.startsWith("- "))).hasSize(PlumeService.MAX_ITEMS);
        assertThat(bounded.prompt()).contains("- c0 suite");
    }
}
