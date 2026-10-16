package fr.claudegateway.atelier.besoin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.WorkspaceSource;
import fr.claudegateway.atelier.deposit.AtelierDepositedFile;
import fr.claudegateway.atelier.deposit.AtelierDepositedFileRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Le registre des besoins de bout en bout (F-205 / SF-205-01) : la carte proposée par l'outil, le clic
 * [Enregistrer], la copie de la pièce, la lecture par l'administrateur (liste, détail, pièce), le
 * changement de statut — et le 403 d'un non-administrateur, le 404 d'un autre compte.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppRequestApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private AtelierDepositedFileRepository deposits;
    @Autowired private AppRequestService service;
    @Autowired private AppRequestAttachmentRepository attachments;
    @Autowired private JwtService jwtService;

    private UUID adminId;
    private String adminToken;
    private String bobToken;
    private Workspace workspace;

    @BeforeEach
    void setUp() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        User admin = save("admin-besoins-" + tag + "@ex.com", UserRole.ADMIN);
        adminId = admin.getId();
        adminToken = jwtService.generateToken(admin);
        bobToken = jwtService.generateToken(save("bob-besoins-" + tag + "@ex.com", UserRole.USER));
        workspace = workspaceRepository.save(Workspace.builder().userId(adminId).name("forge-" + tag)
                .source(WorkspaceSource.ARCHIVE).build());
        String path = workspaceService.depositHostedFile(adminId, workspace.getId(), "capture.png",
                new byte[] {7, 8, 9}, "image/png");
        deposits.save(AtelierDepositedFile.builder().userId(adminId).workspaceId(workspace.getId()).path(path)
                .sizeBytes(3).createdAt(OffsetDateTime.now()).build());
    }

    private User save(String email, UserRole role) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(role).build());
    }

    private UUID propose() {
        return service.propose(adminId, workspace, null, "BUG", "Le terminal se fige",
                "quand je colle une image le terminal se fige", "Collage d'image → gel", "terminal",
                List.of("entrees/capture.png")).requestId();
    }

    private String base(UUID requestId) {
        return "/workspaces/" + workspace.getId() + "/app-requests/" + requestId;
    }

    private void awaitCopy(UUID requestId) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            List<AppRequestAttachment> pieces = attachments.findByRequestIdAndUserIdOrderBySeqAsc(requestId, adminId);
            if (pieces.stream().noneMatch(p -> AppRequestAttachment.PENDING.equals(p.getCopyStatus()))) {
                return;
            }
            Thread.sleep(50);
        }
    }

    @Test
    @DisplayName("proposé → enregistré → copié → lu, téléchargé et cadré par l'administrateur")
    void theFullJourney() throws Exception {
        UUID id = propose();

        mockMvc.perform(get("/admin/app-requests").param("status", "EN_ATTENTE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").isEmpty());

        mockMvc.perform(post(base(id) + "/record").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("EN_ATTENTE")));
        awaitCopy(id);
        assertThat(attachments.findByRequestIdAndUserIdOrderBySeqAsc(id, adminId))
                .extracting(AppRequestAttachment::getStorageKey)
                .containsExactly("besoins/" + id + "/1-capture.png");

        mockMvc.perform(get("/admin/app-requests").param("status", "EN_ATTENTE")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].title").value(
                        org.hamcrest.Matchers.hasItem("Le terminal se fige")))
                .andExpect(jsonPath("$[?(@.id == '" + id + "')].attachments[0].copyStatus").value(
                        org.hamcrest.Matchers.hasItem("COPIED")));

        mockMvc.perform(get("/admin/app-requests/" + id + "/attachments/1")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(new byte[] {7, 8, 9}));

        mockMvc.perform(patch("/admin/app-requests/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CADRE\",\"featureRef\":\"F-210\"}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CADRE")))
                .andExpect(jsonPath("$.featureRef", is("F-210")));

        mockMvc.perform(post(base(id) + "/record").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("[Non] écarte le besoin sans rien copier")
    void dismissing() throws Exception {
        UUID id = propose();

        mockMvc.perform(post(base(id) + "/dismiss").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("REJETE")))
                .andExpect(jsonPath("$.attachments[0].copyStatus").doesNotExist());
    }

    @Test
    @DisplayName("un non-administrateur reçoit 403 sur les routes admin")
    void aPlainUserIsRefused() throws Exception {
        mockMvc.perform(get("/admin/app-requests").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("ISOLATION : Bob ne relit ni ne décide le besoin de l'administrateur")
    void anotherAccountCannotTouchIt() throws Exception {
        UUID id = propose();

        mockMvc.perform(get(base(id)).header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(base(id) + "/record").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("un statut inconnu ou une référence mal formée : 400")
    void badPatch() throws Exception {
        UUID id = propose();
        mockMvc.perform(post(base(id) + "/record").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(patch("/admin/app-requests/" + id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"featureRef\":\"ticket-1\"}")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("app_request_invalid")));
    }
}
