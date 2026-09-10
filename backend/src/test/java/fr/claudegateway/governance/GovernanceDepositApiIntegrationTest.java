package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-51 / SF-51-03 — l'annonce puis le dépôt, de bout en bout, sur un projet en stockage.
 *
 * <p>Deux choses sont vérifiées sur la vraie chaîne : l'aperçu <b>n'écrit rien</b>, et le dépôt
 * <b>n'écrase pas</b> un fichier que l'utilisateur avait déjà.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GovernanceDepositApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private GovernancePackageRepository packages;
    @Autowired private GovernancePackageFileRepository packageFiles;
    @Autowired private GovernanceSelectionRepository selections;
    @Autowired private GovernanceActivationRepository activations;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private UUID aliceId;
    private UUID project;
    private UUID packageId;

    @BeforeEach
    void setUp() {
        activations.deleteAll();
        selections.deleteAll();
        packageFiles.deleteAll();
        packages.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();

        User alice = seedUser("alice-depot@example.com");
        aliceId = alice.getId();
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(seedUser("bob-depot@example.com"));

        // Projet en stockage : `create` sème un CLAUDE.md, ce qui donne un fichier préexistant réel.
        project = workspaceService.create(aliceId, "web", zipWith("STATE.md", "# Mon état à moi\n"))
                .workspace().getId();

        GovernancePackage pkg = packages.save(GovernancePackage.builder()
                .slug("livrables").name("Livrables sans trace").version(1).published(true)
                .rules("Aucun livrable ne doit suggérer un LLM.").build());
        packageId = pkg.getId();
        packageFiles.save(GovernancePackageFile.builder().packageId(packageId).position(0)
                .path("STATE.md").kind(GovernanceFileKind.TEMPLATE).content("# Gabarit du paquet\n")
                .build());
        packageFiles.save(GovernancePackageFile.builder().packageId(packageId).position(1)
                .path(".claude/skills/explique.md").kind(GovernanceFileKind.SKILL)
                .content("# explique\n").build());
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }

    /** Une archive minimale portant un fichier — le « déjà là » que le dépôt ne doit pas écraser. */
    private static byte[] zipWith(String path, String content) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(out)) {
            zip.putNextEntry(new java.util.zip.ZipEntry(path));
            zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
        return out.toByteArray();
    }

    private void retain() throws Exception {
        mockMvc.perform(put("/api/governance/selection/" + packageId).contextPath("/api")
                .header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("l'aperçu annonce CREATE et KEEP, et n'écrit rien")
    void previewAnnouncesWithoutWriting() throws Exception {
        retain();

        mockMvc.perform(get("/api/workspaces/" + project + "/governance/" + packageId + "/preview")
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.readable").value(true))
                .andExpect(jsonPath("$.rules").value(true))
                .andExpect(jsonPath("$.entries", Matchers.hasSize(2)))
                .andExpect(jsonPath("$.entries[0].path").value("STATE.md"))
                .andExpect(jsonPath("$.entries[0].action").value("KEEP"))
                .andExpect(jsonPath("$.entries[1].path").value(".claude/skills/explique.md"))
                .andExpect(jsonPath("$.entries[1].action").value("CREATE"));

        // Rien n'a été écrit : le skill annoncé n'existe toujours pas.
        assertThat(workspaceService.tree(aliceId, project))
                .doesNotContain(".claude/skills/explique.md");
    }

    @Test
    @DisplayName("le dépôt crée le manquant, laisse l'existant intact, et passe APPLIED")
    void depositCreatesAndNeverOverwrites() throws Exception {
        retain();

        mockMvc.perform(post("/api/workspaces/" + project + "/governance/" + packageId)
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active[0].status").value("APPLIED"));

        assertThat(workspaceService.tree(aliceId, project)).contains(".claude/skills/explique.md");
        // Le fichier de l'utilisateur est INTACT, alors que le paquet en apportait un autre.
        assertThat(workspaceService.readFile(aliceId, project, "STATE.md"))
                .isEqualTo("# Mon état à moi\n");
        assertThat(workspaceService.readFile(aliceId, project, ".claude/skills/explique.md"))
                .isEqualTo("# explique\n");
    }

    @Test
    @DisplayName("appliquer une seconde fois ne change rien")
    void applyIsIdempotent() throws Exception {
        retain();
        mockMvc.perform(post("/api/workspaces/" + project + "/governance/" + packageId)
                .contextPath("/api").header("Authorization", "Bearer " + aliceToken));

        mockMvc.perform(post("/api/workspaces/" + project + "/governance/" + packageId + "/apply")
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries[0].action").value("KEEP"))
                .andExpect(jsonPath("$.entries[1].action").value("KEEP"));

        assertThat(workspaceService.readFile(aliceId, project, "STATE.md"))
                .isEqualTo("# Mon état à moi\n");
    }

    @Test
    @DisplayName("appliquer un paquet non actif sur ce projet rend 404")
    void applyOnInactivePackageIsNotFound() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + project + "/governance/" + packageId + "/apply")
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("l'aperçu et l'application sur le projet d'un autre n'écrivent rien")
    void nothingCrossesAccounts() throws Exception {
        retain();

        mockMvc.perform(get("/api/workspaces/" + project + "/governance/" + packageId + "/preview")
                        .contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/workspaces/" + project + "/governance/" + packageId + "/apply")
                        .contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());

        assertThat(workspaceService.tree(aliceId, project))
                .doesNotContain(".claude/skills/explique.md");
        assertThat(activations.findAll()).isEmpty();
    }

    @Test
    @DisplayName("un projet neuf embarque la sélection marquée « appliquée par défaut »")
    void newProjectEmbarksDefaults() throws Exception {
        mockMvc.perform(put("/api/governance/selection/" + packageId).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"defaultApplied\":true}"))
                .andExpect(status().isOk());

        UUID fresh = workspaceService.create(aliceId, "neuf", zipWith("README.md", "hello"))
                .workspace().getId();

        mockMvc.perform(get("/api/workspaces/" + fresh + "/governance").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.active[0].pkg.slug").value("livrables"));
        // Le dépôt a suivi : les deux fichiers du paquet sont là, sans que rien n'ait été coché.
        assertThat(workspaceService.tree(aliceId, fresh))
                .contains("STATE.md", ".claude/skills/explique.md");
    }
}
