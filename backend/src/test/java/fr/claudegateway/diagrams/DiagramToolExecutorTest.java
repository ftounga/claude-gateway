package fr.claudegateway.diagrams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.ProjectFileDeposit;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.diagrams.DiagramRenderer.Format;
import fr.claudegateway.diagrams.DiagramRenderer.Rendered;

/**
 * F-142 / SF-142-06 — le diagramme est rendu par la gateway, et déposé dans le projet.
 *
 * <p>Ce que ces tests protègent : <b>rien n'est exécuté sur le poste</b>, et les trois issues d'un
 * rendu (code invalide, service muet, dépôt en échec) donnent trois phrases différentes — elles
 * n'appellent pas la même suite.</p>
 */
@ExtendWith(MockitoExtension.class)
class DiagramToolExecutorTest {

    @Mock private DiagramRenderer renderer;
    @Mock private ProjectFileDeposit deposit;

    private DiagramToolExecutor executor;
    private final ObjectMapper mapper = new ObjectMapper();
    private final UUID userId = UUID.randomUUID();
    private final Workspace workspace = new Workspace();

    @BeforeEach
    void setUp() {
        executor = new DiagramToolExecutor(renderer, deposit);
        workspace.setId(UUID.randomUUID());
        workspace.setUserId(userId);
    }

    private ObjectNode input(String code, String format) {
        ObjectNode node = mapper.createObjectNode();
        if (code != null) {
            node.put("code", code);
        }
        if (format != null) {
            node.put("format", format);
        }
        return node;
    }

    @Test
    @DisplayName("LE CRITÈRE : le diagramme est rendu côté gateway et déposé dans le projet, chemin rendu à l'agent")
    void rendersAndDeposits() {
        when(renderer.render(anyString(), eq(Format.PNG), any()))
                .thenReturn(new Rendered("PNG".getBytes(StandardCharsets.UTF_8), Format.PNG));
        when(deposit.deposit(eq(userId), eq(workspace), anyString(), anyString(), any(), eq("image/png"),
                eq(DiagramToolCatalog.RENDER))).thenReturn("architecture.png");

        DiagramToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-1",
                input("flowchart TD\n A-->B", null).put("filename", "architecture"));

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.content())
                .contains("architecture.png")
                .contains("add_picture")
                .contains("Rien n'a été installé sur la machine");
        ArgumentCaptor<String> name = ArgumentCaptor.forClass(String.class);
        verify(deposit).deposit(eq(userId), eq(workspace), eq("call-1"), name.capture(), any(),
                eq("image/png"), eq(DiagramToolCatalog.RENDER));
        assertThat(name.getValue()).isEqualTo("architecture.png");
    }

    @Test
    @DisplayName("le SVG est proposé pour une page : format honoré, type de contenu qui va avec")
    void svgIsHonoured() {
        when(renderer.render(anyString(), eq(Format.SVG), any()))
                .thenReturn(new Rendered("<svg/>".getBytes(StandardCharsets.UTF_8), Format.SVG));
        when(deposit.deposit(any(), any(), anyString(), anyString(), any(), eq("image/svg+xml"), anyString()))
                .thenReturn("schema.svg");

        DiagramToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-2",
                input("sequenceDiagram\n A->>B: salut", "svg").put("filename", "schema"));

        assertThat(outcome.error()).isFalse();
        assertThat(outcome.content()).contains("schema.svg");
    }

    @Test
    @DisplayName("code invalide : la RAISON du moteur est rendue, et RIEN n'est déposé")
    void aninvalidDiagramSaysWhy() {
        when(renderer.render(anyString(), any(), any()))
                .thenThrow(new DiagramRejectedException("Parse error on line 2"));

        DiagramToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-3",
                input("flowchart TD\n A--", null));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("Parse error on line 2").contains("Corrige le code");
        verify(deposit, never()).deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("service muet : autre phrase, autre suite — le repli par la PAGE est proposé")
    void asilentServiceProposesTheFallback() {
        when(renderer.render(anyString(), any(), any()))
                .thenThrow(new DiagramRendererUnavailableException("connexion refusée"));

        DiagramToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-4",
                input("flowchart TD\n A-->B", null));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content())
                .contains("indisponible")
                .contains("PAGE")
                .contains("Ne fabrique pas d'image");
        verify(deposit, never()).deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString());
    }

    @Test
    @DisplayName("dépôt en échec : l'image existe mais n'est pas arrivée — et c'est dit")
    void afailedDepositIsSaid() {
        when(renderer.render(anyString(), any(), any()))
                .thenReturn(new Rendered("PNG".getBytes(StandardCharsets.UTF_8), Format.PNG));
        when(deposit.deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(null);

        DiagramToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-5",
                input("flowchart TD\n A-->B", null));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("dépôt").contains("page");
    }

    @Test
    @DisplayName("sans code : refus immédiat, aucun rendu tenté")
    void withoutCodeNothingIsAttempted() {
        DiagramToolExecutor.Outcome outcome = executor.execute(userId, workspace, "call-6",
                input(null, null));

        assertThat(outcome.error()).isTrue();
        assertThat(outcome.content()).contains("code est requis");
        verify(renderer, never()).render(anyString(), any(), any());
    }

    @Test
    @DisplayName("ISOLATION : un nom de fichier venu du modèle est NETTOYÉ — jamais un chemin")
    void thefileNameIsCleaned() {
        assertThat(DiagramToolExecutor.fileName("../../etc/passwd", Format.PNG)).isEqualTo("passwd.png");
        assertThat(DiagramToolExecutor.fileName("C:\\\\Windows\\\\archi.png", Format.PNG)).isEqualTo("archi.png");
        assertThat(DiagramToolExecutor.fileName("mon archi (v2)", Format.SVG)).isEqualTo("mon-archi-v2.svg");
        assertThat(DiagramToolExecutor.fileName(null, Format.PNG)).startsWith("diagramme-").endsWith(".png");
    }
}
