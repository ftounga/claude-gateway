package fr.claudegateway.diagrams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceSource;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-238 / SF-238-07 — le graphe conservé : rangé avec la ligne du rendu (cloud, drawio), lu par son SEUL propriétaire
 * (isolation A/B, aucun calcul déclenché pour B), calculé une fois pour une ligne ancienne — identique à un rendu
 * neuf —, jamais pour Mermaid.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DiagramRenderGraphIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private DiagramToolExecutor executor;
    @Autowired private DiagramRenderRepository renders;
    @Autowired private JwtService jwt;
    @MockitoBean private DiagramRenderer renderer;

    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"/>";
    private static final String GRAPH = "{\"version\":1,\"title\":\"Edenred en prod\",\"direction\":\"TB\","
            + "\"bounds\":{\"w\":400,\"h\":300},\"zones\":[{\"id\":\"vpc\",\"kind\":\"vpc\",\"label\":\"VPC\","
            + "\"parent\":\"\",\"depth\":0,\"box\":{\"x\":0,\"y\":0,\"w\":400,\"h\":300},\"inferred\":false}],"
            + "\"nodes\":[{\"id\":\"db\",\"type\":\"aws.rds\",\"resolved\":\"aws/database/rds\",\"label\":\"Base\","
            + "\"zone\":\"vpc\",\"family\":\"database\",\"unknown\":false,\"box\":{\"x\":10,\"y\":10,\"w\":128,"
            + "\"h\":112}}],\"edges\":[],\"notices\":[]}";
    private final ObjectMapper mapper = new ObjectMapper();

    private User alice;
    private String aliceToken;
    private String bobToken;
    private Workspace subject;

    private User user(String prefix) {
        return users.save(User.builder().email(prefix + "-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).createdAt(OffsetDateTime.now()).build());
    }

    @BeforeEach
    void seed() {
        alice = user("graph-alice");
        aliceToken = jwt.generateToken(alice);
        bobToken = jwt.generateToken(user("graph-bob"));
        subject = workspaces.save(Workspace.builder().userId(alice.getId()).name("Edenred")
                .projectPath("p-" + UUID.randomUUID()).source(WorkspaceSource.ARCHIVE)
                .executionTarget(WorkspaceExecutionTarget.SANDBOX).build());
        when(renderer.isAvailable()).thenReturn(true);
    }

    private JsonNode spec() throws Exception {
        return mapper.readTree("{\"title\":\"Edenred en prod\",\"groups\":[{\"id\":\"vpc\",\"label\":\"VPC\","
                + "\"kind\":\"vpc\"}],\"nodes\":[{\"id\":\"db\",\"type\":\"aws.rds\",\"label\":\"Base\","
                + "\"group\":\"vpc\"}],\"edges\":[]}");
    }

    private DiagramRender renderCloud(String callId) throws Exception {
        when(renderer.renderCloud(any())).thenReturn(new DiagramRenderer.Rendered(
                SVG.getBytes(StandardCharsets.UTF_8), DiagramRenderer.Format.SVG, "", "", mapper.readTree(GRAPH)));
        JsonNode input = mapper.createObjectNode().put("engine", "cloud").put("filename", "archi")
                .set("spec", spec());
        assertThat(executor.execute(alice.getId(), subject, callId, input).error()).isFalse();
        return renders.findTop50ByUserIdAndWorkspaceIdOrderByCreatedAtDesc(alice.getId(), subject.getId()).stream()
                .filter(d -> callId.equals(d.getCallId())).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("rendu cloud : graphe rangé dans la ligne (compteurs, version), servi au propriétaire, 404 pour B")
    void keptServedAndIsolated() throws Exception {
        DiagramRender kept = renderCloud("toolu_g1");
        assertThat(kept.getGraphJson()).isEqualTo(mapper.writeValueAsString(mapper.readTree(GRAPH)));
        assertThat(kept.getGraphVersion()).isEqualTo((short) 1);
        assertThat(kept.getNodeCount()).isEqualTo(1);
        assertThat(kept.getZoneCount()).isEqualTo(1);
        assertThat(kept.getEdgeCount()).isZero();
        String pieceId = "diag:" + kept.getId();

        mockMvc.perform(get("/api/jarvis/pieces/" + pieceId + "/graph").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.nodes[0].resolved").value("aws/database/rds"));

        // ISOLATION A/B
        mockMvc.perform(get("/api/jarvis/pieces/" + pieceId + "/graph").contextPath("/api")
                .header("Authorization", "Bearer " + bobToken)).andExpect(status().isNotFound());

        mockMvc.perform(get("/api/jarvis/pieces?subjectId=" + subject.getId() + "&kind=architecture")
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.pieces[0].meta.graph").value("/jarvis/pieces/" + pieceId + "/graph"))
                .andExpect(jsonPath("$.pieces[0].meta.nodes").value(1))
                .andExpect(jsonPath("$.pieces[0].meta.zones").value(1))
                .andExpect(jsonPath("$.pieces[0].meta.edges").value(0));
        verify(renderer, never()).computeGraph(anyString(), any());
    }

    @Test
    @DisplayName("ligne ancienne : graphe calculé UNE fois, identique à un rendu neuf ; B ne déclenche aucun calcul")
    void olderRowComputedOnceIdentical() throws Exception {
        DiagramRender fresh = renderCloud("toolu_g2");
        DiagramRender old = renders.save(DiagramRender.builder().id(UUID.randomUUID()).userId(alice.getId())
                .workspaceId(subject.getId()).engine("cloud").title("Edenred en prod")
                .specJson(spec().toString()).svgKey("diagrams/x.svg").createdAt(OffsetDateTime.now()).build());
        when(renderer.computeGraph(anyString(), any())).thenReturn(Optional.of(mapper.readTree(GRAPH)));
        String pieceId = "diag:" + old.getId();

        // B d'abord : 404, et aucun calcul n'est déclenché pour un autre compte
        mockMvc.perform(get("/api/jarvis/pieces/" + pieceId + "/graph").contextPath("/api")
                .header("Authorization", "Bearer " + bobToken)).andExpect(status().isNotFound());
        verify(renderer, never()).computeGraph(anyString(), any());

        String first = mockMvc.perform(get("/api/jarvis/pieces/" + pieceId + "/graph").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String second = mockMvc.perform(get("/api/jarvis/pieces/" + pieceId + "/graph").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(first).isEqualTo(second).isEqualTo(fresh.getGraphJson());
        ArgumentCaptor<JsonNode> sent = ArgumentCaptor.forClass(JsonNode.class);
        verify(renderer, times(1)).computeGraph(org.mockito.ArgumentMatchers.eq("cloud"), sent.capture());
        assertThat(sent.getValue()).isEqualTo(spec()); // la spec conservée, telle quelle
        DiagramRender stored = renders.findById(old.getId()).orElseThrow();
        assertThat(stored.getGraphJson()).isEqualTo(fresh.getGraphJson());
        assertThat(stored.getNodeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("calcul en échec : 404, rien n'est rangé (un appel suivant réessaie)")
    void failedComputationStoresNothing() throws Exception {
        DiagramRender old = renders.save(DiagramRender.builder().id(UUID.randomUUID()).userId(alice.getId())
                .workspaceId(subject.getId()).engine("drawio").title("t").specJson(spec().toString())
                .createdAt(OffsetDateTime.now()).build());
        when(renderer.computeGraph(anyString(), any()))
                .thenThrow(new DiagramRendererUnavailableException("muet"));

        mockMvc.perform(get("/api/jarvis/pieces/diag:" + old.getId() + "/graph").contextPath("/api")
                .header("Authorization", "Bearer " + aliceToken)).andExpect(status().isNotFound());
        assertThat(renders.findById(old.getId()).orElseThrow().getGraphJson()).isNull();
    }

    @Test
    @DisplayName("Mermaid : aucune colonne du graphe écrite, /graph ⇒ 404, pas de meta.graph")
    void mermaidWritesNothing() throws Exception {
        when(renderer.render(any(), any(), any())).thenReturn(
                new DiagramRenderer.Rendered(SVG.getBytes(StandardCharsets.UTF_8), DiagramRenderer.Format.SVG));
        executor.execute(alice.getId(), subject, "toolu_g4",
                mapper.readTree("{\"code\":\"flowchart LR\\n A-->B\",\"format\":\"svg\"}"));
        DiagramRender kept = renders.findTop50ByUserIdAndWorkspaceIdOrderByCreatedAtDesc(alice.getId(),
                subject.getId()).get(0);
        assertThat(kept.getGraphJson()).isNull();
        assertThat(kept.getGraphVersion()).isNull();
        assertThat(kept.getNodeCount()).isNull();
        mockMvc.perform(get("/api/jarvis/pieces/diag:" + kept.getId() + "/graph").contextPath("/api")
                .header("Authorization", "Bearer " + aliceToken)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/jarvis/pieces?subjectId=" + subject.getId() + "&kind=diagram").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.pieces[0].meta.graph").doesNotExist());
        verify(renderer, never()).computeGraph(anyString(), any());
    }

    @Test
    @DisplayName("rendu refusé : ni ligne ni graphe")
    void refusedRenderKeepsNothing() throws Exception {
        when(renderer.renderCloud(any())).thenThrow(new DiagramRejectedException("AZ hors VPC"));
        JsonNode input = mapper.createObjectNode().put("engine", "cloud").set("spec", spec());
        assertThat(executor.execute(alice.getId(), subject, "toolu_g5", input).error()).isTrue();
        assertThat(renders.findTop50ByUserIdAndWorkspaceIdOrderByCreatedAtDesc(alice.getId(), subject.getId()))
                .isEmpty();
    }
}
