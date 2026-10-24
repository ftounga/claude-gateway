package fr.claudegateway.atelier.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
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

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.WorkspaceSource;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-212 / SF-212-03 — l'image d'une capture de preuve : servie à son propriétaire, introuvable pour un
 * autre compte (indiscernable d'une capture inexistante), effacée avec le terminal ; secrets masqués
 * avant rendu.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProofCaptureApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private ProofCaptureService proofs;
    @Autowired private ProofCaptureRepository repository;
    @Autowired private JwtService jwtService;
    @MockitoBean private TerminalShotRenderer renderer;

    private static final byte[] PNG = "png-bytes".getBytes(StandardCharsets.UTF_8);
    private static final String REPORT = "{\"os\":\"linux\",\"host\":\"pc\",\"execShell\":\"bash\",\"commands\":["
            + "{\"command\":\"echo $TOKEN\",\"status\":\"done\",\"exitCode\":0,\"durationMs\":40,"
            + "\"stdout\":\"ghp_abcdefghijklmnopqrstuvwxyz0123\\n\",\"stderr\":\"\"}]}";

    private UUID aliceId;
    private String aliceToken;
    private UUID aliceTerminal;
    private String bobToken;
    private UUID bobTerminal;
    private final java.util.concurrent.atomic.AtomicReference<String> rendered =
            new java.util.concurrent.atomic.AtomicReference<>();

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();
        User alice = seedUser("alice-proofs@example.com");
        aliceId = alice.getId();
        aliceToken = jwtService.generateToken(alice);
        aliceTerminal = seedTerminal(aliceId);
        User bob = seedUser("bob-proofs@example.com");
        bobToken = jwtService.generateToken(bob);
        bobTerminal = seedTerminal(bob.getId());
        when(renderer.shoot(any())).thenAnswer(invocation -> {
            rendered.set(invocation.getArgument(0).toString());
            return List.of(new TerminalShotRenderer.Shot(PNG, 800, 400));
        });
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }

    private UUID seedTerminal(UUID userId) {
        return workspaceRepository.save(Workspace.builder().userId(userId).name("Projet").projectPath("projet")
                .source(WorkspaceSource.LOCAL).executionTarget(WorkspaceExecutionTarget.RUNNER).build()).getId();
    }

    private String url(UUID workspaceId, String captureId) {
        return "/api/workspaces/" + workspaceId + "/proofs/" + captureId;
    }

    private String capture() {
        ProofCaptureService.Result result = proofs.capture(aliceId, aliceTerminal, "toolu_1", "Preuve", null, REPORT);
        assertThat(result.block()).isNotNull();
        return result.block().captures().get(0).id();
    }

    @Test
    @DisplayName("l'image se lit, en PNG, par son propriétaire ; les secrets sont masqués AVANT le rendu")
    void ownerReadsTheImage() throws Exception {
        String id = capture();
        mockMvc.perform(get(url(aliceTerminal, id)).contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PNG));
        assertThat(rendered.get()).doesNotContain("ghp_abcdefghijklmnopqrstuvwxyz0123").contains("masqué");
    }

    @Test
    @DisplayName("LA CAPTURE D'UN AUTRE COMPTE EST INTROUVABLE — comme une capture inexistante")
    void anotherAccountGets404() throws Exception {
        String id = capture();
        mockMvc.perform(get(url(bobTerminal, id)).contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(url(aliceTerminal, id)).contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(url(aliceTerminal, "pas-un-identifiant")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(url(aliceTerminal, "0".repeat(32))).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("supprimer le terminal efface ses captures (images et lignes)")
    void deletingTheTerminalErasesCaptures() {
        String id = capture();
        assertThat(proofs.image(aliceId, aliceTerminal, id)).isPresent();
        workspaceService.delete(aliceId, aliceTerminal);
        assertThat(proofs.image(aliceId, aliceTerminal, id)).isEmpty();
        assertThat(repository.findAll()).isEmpty();
    }
}
