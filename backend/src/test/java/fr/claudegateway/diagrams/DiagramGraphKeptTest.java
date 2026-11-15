package fr.claudegateway.diagrams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.atelier.ProjectFileDeposit;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.journey.SubjectJourneyRepository;
import fr.claudegateway.atelier.storage.WorkspaceStorage;
import fr.claudegateway.diagrams.DiagramRenderer.Format;
import fr.claudegateway.diagrams.DiagramRenderer.Rendered;

/**
 * F-238 / SF-238-07 — le graphe d'architecture voyage du rendu à la ligne conservée : transmis pour cloud et drawio,
 * jamais sans dépôt réussi, jamais pour Mermaid ; colonnes justes, graphe illisible ou trop lourd non rangé.
 */
class DiagramGraphKeptTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private DiagramRenderer renderer;
    private ProjectFileDeposit deposit;
    private DiagramRenderService renders;
    private DiagramToolExecutor executor;
    private final UUID userId = UUID.randomUUID();
    private final Workspace workspace = new Workspace();

    @BeforeEach
    void setUp() {
        renderer = mock(DiagramRenderer.class);
        deposit = mock(ProjectFileDeposit.class);
        renders = mock(DiagramRenderService.class);
        executor = new DiagramToolExecutor(renderer, deposit);
        executor.setRenders(renders);
        workspace.setId(UUID.randomUUID());
        workspace.setUserId(userId);
    }

    private JsonNode graph() throws Exception {
        return mapper.readTree("{\"version\":1,\"zones\":[{\"id\":\"vpc\"},{\"id\":\"az\"}],"
                + "\"nodes\":[{\"id\":\"a\"},{\"id\":\"b\"},{\"id\":\"c\"}],\"edges\":[{\"id\":\"e1\"}]}");
    }

    private ObjectNode input(String engine) {
        ObjectNode input = mapper.createObjectNode().put("engine", engine).put("filename", "archi");
        input.putObject("spec").putArray("nodes").addObject().put("id", "a").put("type", "aws.s3").put("label", "A");
        return input;
    }

    @Test
    @DisplayName("cloud : le graphe du rendu est transmis à la conservation, après le dépôt")
    void cloudPassesTheGraph() throws Exception {
        JsonNode graph = graph();
        when(renderer.renderCloud(any())).thenReturn(new Rendered("<svg/>".getBytes(StandardCharsets.UTF_8),
                Format.SVG, "", "", graph));
        when(deposit.deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("archi.svg");

        assertThat(executor.execute(userId, workspace, "c1", input("cloud")).error()).isFalse();

        verify(renders).record(eq(userId), eq(workspace), eq("c1"), eq("cloud"), any(), isNull(), anyString(), any(),
                eq(graph));
    }

    @Test
    @DisplayName("drawio : le graphe de la réponse est transmis")
    void drawioPassesTheGraph() throws Exception {
        JsonNode graph = graph();
        when(renderer.renderEditable(any())).thenReturn(new DiagramRenderer.Editable(
                "<mxfile/>".getBytes(StandardCharsets.UTF_8), new byte[0], "", graph));
        when(deposit.deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn("archi.drawio");

        executor.execute(userId, workspace, "c2", input("drawio"));

        verify(renders).record(eq(userId), eq(workspace), eq("c2"), eq("drawio"), any(), isNull(), anyString(), any(),
                eq(graph));
    }

    @Test
    @DisplayName("pas de graphe sans dépôt réussi : dépôt en échec ⇒ rien n'est conservé")
    void noGraphWithoutDeposit() throws Exception {
        when(renderer.renderCloud(any())).thenReturn(new Rendered("<svg/>".getBytes(StandardCharsets.UTF_8),
                Format.SVG, "", "", graph()));
        when(deposit.deposit(any(), any(), anyString(), anyString(), any(), anyString(), anyString()))
                .thenReturn(null);

        assertThat(executor.execute(userId, workspace, "c3", input("cloud")).error()).isTrue();

        verify(renders, never()).record(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    private DiagramRenderService realService() {
        return new DiagramRenderService(mock(DiagramRenderRepository.class), mock(WorkspaceStorage.class),
                mock(SubjectJourneyRepository.class), renderer, mapper, Clock.systemUTC());
    }

    @Test
    @DisplayName("colonnes : JSON compact, version, compteurs nœuds/zones/liens")
    void columns() throws Exception {
        DiagramRenderService.GraphColumns columns = realService().columnsOf(graph());

        assertThat(columns.version()).isEqualTo((short) 1);
        assertThat(columns.nodes()).isEqualTo(3);
        assertThat(columns.zones()).isEqualTo(2);
        assertThat(columns.edges()).isEqualTo(1);
        assertThat(columns.json()).doesNotContain(" ").isEqualTo(mapper.writeValueAsString(graph()));
    }

    @Test
    @DisplayName("graphe illisible (pas d'objet, version ou nœuds absents) ⇒ non rangé")
    void unreadableGraphIsNotKept() throws Exception {
        DiagramRenderService service = realService();
        assertThat(service.columnsOf(null)).isNull();
        assertThat(service.columnsOf(mapper.readTree("[1]"))).isNull();
        assertThat(service.columnsOf(mapper.readTree("{\"nodes\":[]}"))).isNull();
        assertThat(service.columnsOf(mapper.readTree("{\"version\":\"1\",\"nodes\":[]}"))).isNull();
        assertThat(service.columnsOf(mapper.readTree("{\"version\":1}"))).isNull();
    }

    @Test
    @DisplayName("plafond : un graphe > 64 Kio n'est pas rangé, juste en dessous il l'est")
    void graphCeiling() throws Exception {
        ObjectNode big = mapper.createObjectNode().put("version", 1);
        ArrayNode nodes = big.putArray("nodes");
        String filler = "x".repeat(1000);
        for (int i = 0; i < 70; i++) {
            nodes.addObject().put("id", "n" + i).put("label", filler);
        }
        assertThat(mapper.writeValueAsString(big).length()).isGreaterThan(DiagramRenderService.MAX_GRAPH_BYTES);
        assertThat(realService().columnsOf(big)).isNull();

        ObjectNode fits = mapper.createObjectNode().put("version", 1);
        ArrayNode some = fits.putArray("nodes");
        for (int i = 0; i < 60; i++) {
            some.addObject().put("id", "n" + i).put("label", filler);
        }
        assertThat(mapper.writeValueAsString(fits).length()).isLessThan(DiagramRenderService.MAX_GRAPH_BYTES);
        assertThat(realService().columnsOf(fits)).isNotNull();
    }
}
