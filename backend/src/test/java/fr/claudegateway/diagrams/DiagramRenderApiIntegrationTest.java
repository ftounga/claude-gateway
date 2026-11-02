package fr.claudegateway.diagrams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.WorkspaceSource;
import fr.claudegateway.atelier.storage.WorkspaceStorage;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-237 / SF-237-03 — conserver les schémas : écrit après un rendu RÉUSSI seulement, montré par l'index (type
 * ARCHITECTURE, groupes de premier niveau), image et spec lisibles par leur seul propriétaire, effacés avec le sujet.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DiagramRenderApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private WorkspaceRepository workspaces;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private DiagramToolExecutor executor;
    @Autowired private DiagramRenderRepository renders;
    @Autowired private WorkspaceStorage storage;
    @Autowired private JwtService jwt;
    @MockitoBean private DiagramRenderer renderer;

    private static final String SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\"><g id=\"vpc-prod\"/></svg>";
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
        alice = user("diag-alice");
        aliceToken = jwt.generateToken(alice);
        bobToken = jwt.generateToken(user("diag-bob"));
        subject = workspaces.save(Workspace.builder().userId(alice.getId()).name("Edenred")
                .projectPath("p-" + UUID.randomUUID()).source(WorkspaceSource.ARCHIVE)
                .executionTarget(WorkspaceExecutionTarget.SANDBOX).build());
    }

    private JsonNode cloudInput() throws Exception {
        return mapper.readTree("{\"engine\":\"cloud\",\"filename\":\"archi\",\"spec\":{\"title\":\"Edenred en prod\","
                + "\"groups\":[{\"id\":\"vpc\",\"label\":\"VPC de prod\"},{\"id\":\"sub\",\"label\":\"Sous-réseau\","
                + "\"parent\":\"vpc\"},{\"id\":\"onprem\",\"label\":\"Sur site\"}],"
                + "\"nodes\":[{\"id\":\"db\",\"type\":\"aws.rds\",\"label\":\"Base\",\"group\":\"sub\"}],\"edges\":[]}}");
    }

    @Test
    @DisplayName("un rendu réussi est conservé et montré : ARCHITECTURE, titre + groupes de premier niveau")
    void keptAndIndexed() throws Exception {
        when(renderer.renderCloud(any())).thenReturn(
                new DiagramRenderer.Rendered(SVG.getBytes(StandardCharsets.UTF_8), DiagramRenderer.Format.SVG));
        DiagramToolExecutor.Outcome outcome = executor.execute(alice.getId(), subject, "toolu_diag1", cloudInput());
        assertThat(outcome.error()).isFalse();

        DiagramRender kept = renders.findTop50ByUserIdAndWorkspaceIdOrderByCreatedAtDesc(alice.getId(),
                subject.getId()).get(0);
        assertThat(kept.getEngine()).isEqualTo("cloud");
        assertThat(kept.getTitle()).isEqualTo("Edenred en prod");
        assertThat(kept.getCallId()).isEqualTo("toolu_diag1");
        assertThat(kept.getSvgKey()).startsWith(DiagramRenderService.prefixOf(alice.getId(), subject.getId()));
        String pieceId = "diag:" + kept.getId();

        mockMvc.perform(get("/api/jarvis/pieces?subjectId=" + subject.getId() + "&kind=architecture")
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.pieces[0].id").value(pieceId))
                .andExpect(jsonPath("$.pieces[0].category").value("PRODUCTION"))
                .andExpect(jsonPath("$.pieces[0].caption").value("Edenred en prod — VPC de prod, Sur site"))
                .andExpect(jsonPath("$.pieces[0].captionSource").value("SPEC"))
                .andExpect(jsonPath("$.pieces[0].open").value("/jarvis/pieces/" + pieceId + "/image"))
                .andExpect(jsonPath("$.pieces[0].meta.spec").value("/jarvis/pieces/" + pieceId + "/spec"));

        mockMvc.perform(get("/api/jarvis/pieces/" + pieceId + "/image").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/svg+xml"))
                .andExpect(header().string("Content-Security-Policy",
                        org.hamcrest.Matchers.containsString("default-src 'none'")))
                .andExpect(content().string(SVG));
        mockMvc.perform(get("/api/jarvis/pieces/" + pieceId + "/spec").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.groups[0].label").value("VPC de prod"));

        // ISOLATION A/B
        mockMvc.perform(get("/api/jarvis/pieces/" + pieceId + "/image").contextPath("/api")
                .header("Authorization", "Bearer " + bobToken)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/jarvis/pieces/" + pieceId + "/spec").contextPath("/api")
                .header("Authorization", "Bearer " + bobToken)).andExpect(status().isNotFound());

        // effacé avec le sujet : la ligne (cascade) et les fichiers (préfixe)
        workspaceService.delete(alice.getId(), subject.getId());
        assertThat(renders.findById(kept.getId())).isEmpty();
        assertThat(storage.listKeys(DiagramRenderService.prefixOf(alice.getId(), subject.getId()))).isEmpty();
    }

    @Test
    @DisplayName("un rendu refusé n'est jamais conservé")
    void failedRenderIsNotKept() throws Exception {
        when(renderer.renderCloud(any())).thenThrow(new DiagramRejectedException("spec invalide"));
        DiagramToolExecutor.Outcome outcome = executor.execute(alice.getId(), subject, "toolu_diag2", cloudInput());
        assertThat(outcome.error()).isTrue();
        assertThat(renders.findTop50ByUserIdAndWorkspaceIdOrderByCreatedAtDesc(alice.getId(), subject.getId()))
                .isEmpty();
    }

    @Test
    @DisplayName("Mermaid : DIAGRAM, code conservé, titre = première ligne ; pas de spec servie")
    void mermaid() throws Exception {
        when(renderer.render(any(), any(), any())).thenReturn(
                new DiagramRenderer.Rendered(SVG.getBytes(StandardCharsets.UTF_8), DiagramRenderer.Format.SVG));
        executor.execute(alice.getId(), subject, "toolu_diag3",
                mapper.readTree("{\"code\":\"flowchart LR\\n A-->B\",\"format\":\"svg\"}"));
        DiagramRender kept = renders.findTop50ByUserIdAndWorkspaceIdOrderByCreatedAtDesc(alice.getId(),
                subject.getId()).get(0);
        assertThat(kept.isArchitecture()).isFalse();
        assertThat(kept.getCode()).contains("A-->B");
        assertThat(kept.getTitle()).isEqualTo("flowchart LR");
        mockMvc.perform(get("/api/jarvis/pieces/diag:" + kept.getId() + "/spec").contextPath("/api")
                .header("Authorization", "Bearer " + aliceToken)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/jarvis/pieces?subjectId=" + subject.getId() + "&kind=diagram").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.total").value(1));
    }
}
