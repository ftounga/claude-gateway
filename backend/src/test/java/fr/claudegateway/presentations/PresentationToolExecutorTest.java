package fr.claudegateway.presentations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.exec.RunnerToolGateway;

/**
 * L'exécuteur de {@code presentation_publish} (F-129 / SF-129-02). Le patron de lecture est celui de
 * {@code PageToolExecutor} ; ces tests couvrent la logique propre à la capture : validations d'entrée,
 * lecture hébergée (sandbox), et refus nommés — sans dépendre d'un vrai runner.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PresentationToolExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock private PresentationService presentationService;
    @Mock private RunnerToolGateway runnerToolGateway;
    @Mock private RunnerAuditService runnerAuditService;
    @Mock private WorkspaceService workspaceService;
    @Mock private Workspace workspace;

    private PresentationToolExecutor executor;
    private final UUID userId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        executor = new PresentationToolExecutor(presentationService, runnerToolGateway, runnerAuditService,
                workspaceService, new PresentationLimits(26_214_400L, 100, 5_242_880L));
        when(workspace.getId()).thenReturn(workspaceId);
        when(workspace.getHostId()).thenReturn(hostId);
        when(workspace.isTeamsTerminal()).thenReturn(false);
    }

    private JsonNode input(String... pairs) {
        ObjectNode node = MAPPER.createObjectNode();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            node.put(pairs[i], pairs[i + 1]);
        }
        return node;
    }

    @Test
    @DisplayName("path absent → erreur nommée, rien n'est rangé")
    void pathRequired() {
        PresentationToolExecutor.Outcome outcome = executor.execute(userId, workspace, "c1",
                input("title", "Deck"));
        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("path");
        verify(presentationService, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("title absent → erreur nommée")
    void titleRequired() {
        PresentationToolExecutor.Outcome outcome = executor.execute(userId, workspace, "c1",
                input("path", "deck.pptx"));
        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("title");
    }

    @Test
    @DisplayName("extension ≠ .pptx → erreur nommée, aucune lecture")
    void notPptx() {
        PresentationToolExecutor.Outcome outcome = executor.execute(userId, workspace, "c1",
                input("title", "Deck", "path", "deck.pdf"));
        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains(".pptx");
        verify(presentationService, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("projet hébergé (sandbox) : le .pptx est lu par le stockage et rangé")
    void sandboxReadPublishes() {
        when(workspace.isRunnerTarget()).thenReturn(false);
        byte[] bytes = "PKdeck".getBytes(StandardCharsets.ISO_8859_1);
        when(workspaceService.readFileBytes(userId, workspaceId, "out/deck.pptx")).thenReturn(bytes);
        Presentation saved = Presentation.builder().id(UUID.randomUUID()).title("Deck").build();
        when(presentationService.publish(any(), eq(null), eq("Deck"), any(), eq(bytes))).thenReturn(saved);

        PresentationToolExecutor.Outcome outcome = executor.execute(userId, workspace, "c1",
                input("title", "Deck", "path", "out/deck.pptx"));

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.published()).isSameAs(saved);
        ArgumentCaptor<PresentationPlace> place = ArgumentCaptor.forClass(PresentationPlace.class);
        verify(presentationService).publish(place.capture(), eq(null), eq("Deck"), any(), eq(bytes));
        // Isolation : le lieu porte l'utilisateur du tour et le poste du terminal, jamais un paramètre client.
        assertThat(place.getValue().userId()).isEqualTo(userId);
        assertThat(place.getValue().hostId()).isEqualTo(hostId);
        assertThat(place.getValue().space()).isEqualTo(PresentationSpace.FORGE);
    }

    @Test
    @DisplayName("fichier illisible dans le projet hébergé → erreur nommée")
    void sandboxReadFailure() {
        when(workspace.isRunnerTarget()).thenReturn(false);
        when(workspaceService.readFileBytes(userId, workspaceId, "deck.pptx"))
                .thenThrow(new IllegalStateException("absent"));

        PresentationToolExecutor.Outcome outcome = executor.execute(userId, workspace, "c1",
                input("title", "Deck", "path", "deck.pptx"));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("illisible");
        verify(presentationService, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("presentation_id mal formé → erreur nommée, aucune lecture")
    void malformedId() {
        PresentationToolExecutor.Outcome outcome = executor.execute(userId, workspace, "c1",
                input("title", "Deck", "path", "deck.pptx", "presentation_id", "pas-un-uuid"));
        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("presentation_id");
    }

    // ------------------------------------------------------------ SF-129-03 : le rendu par slides

    private JsonNode inputWithSlides(String... slidePaths) {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("title", "Deck");
        node.put("path", "deck.pptx");
        node.set("slides", MAPPER.valueToTree(slidePaths));
        return node;
    }

    @Test
    @DisplayName("slides fournies : lues (sandbox) et attachées, slide_count posé")
    void slidesReadAndAttached() {
        when(workspace.isRunnerTarget()).thenReturn(false);
        byte[] pptx = "PKdeck".getBytes(StandardCharsets.ISO_8859_1);
        when(workspaceService.readFileBytes(userId, workspaceId, "deck.pptx")).thenReturn(pptx);
        when(workspaceService.readFileBytes(userId, workspaceId, "slide-1.png"))
                .thenReturn("img1".getBytes(StandardCharsets.UTF_8));
        when(workspaceService.readFileBytes(userId, workspaceId, "slide-2.png"))
                .thenReturn("img2".getBytes(StandardCharsets.UTF_8));
        UUID id = UUID.randomUUID();
        Presentation saved = Presentation.builder().id(id).title("Deck").build();
        when(presentationService.publish(any(), eq(null), eq("Deck"), any(), eq(pptx))).thenReturn(saved);
        Presentation withSlides = Presentation.builder().id(id).title("Deck").slideCount(2).build();
        when(presentationService.attachSlides(eq(userId), eq(id), any())).thenReturn(withSlides);

        PresentationToolExecutor.Outcome outcome = executor.execute(userId, workspace, "c1",
                inputWithSlides("slide-1.png", "slide-2.png"));

        assertThat(outcome.error()).isFalse();
        ArgumentCaptor<java.util.List<byte[]>> images = ArgumentCaptor.forClass(java.util.List.class);
        verify(presentationService).attachSlides(eq(userId), eq(id), images.capture());
        assertThat(images.getValue()).hasSize(2);
        assertThat(outcome.content()).contains("2 slides");
    }

    @Test
    @DisplayName("plus de max-slides → erreur, rien n'est publié ni lu")
    void tooManySlides() {
        String[] many = new String[101];
        for (int i = 0; i < many.length; i++) {
            many[i] = "slide-" + i + ".png";
        }
        PresentationToolExecutor.Outcome outcome = executor.execute(userId, workspace, "c1",
                inputWithSlides(many));
        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("Trop de slides");
        verify(presentationService, never()).publish(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("une slide non-png → erreur nommée")
    void slideNotPng() {
        when(workspace.isRunnerTarget()).thenReturn(false);
        when(workspaceService.readFileBytes(userId, workspaceId, "deck.pptx"))
                .thenReturn("PK".getBytes(StandardCharsets.ISO_8859_1));
        PresentationToolExecutor.Outcome outcome = executor.execute(userId, workspace, "c1",
                inputWithSlides("slide-1.jpg"));
        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains(".png");
        verify(presentationService, never()).attachSlides(any(), any(), any());
    }
}
