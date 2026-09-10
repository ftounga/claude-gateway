package fr.claudegateway.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
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

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-51 / SF-51-02 — le catalogue personnel et l'activation, de bout en bout.
 *
 * <p>Ce que ce test protège avant tout : <b>rien ne traverse un compte</b>. Bob ne voit pas le
 * catalogue d'Alice, ne lit pas la gouvernance de son projet, et ne peut rien y activer — et surtout,
 * une tentative n'écrit <b>aucune ligne</b>.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GovernanceSelectionApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private GovernancePackageRepository packages;
    @Autowired private GovernancePackageFileRepository packageFiles;
    @Autowired private GovernanceSelectionRepository selections;
    @Autowired private GovernanceActivationRepository activations;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private UUID aliceId;
    private UUID aliceProject;
    private UUID publishedId;
    private UUID draftId;

    @BeforeEach
    void setUp() {
        activations.deleteAll();
        selections.deleteAll();
        packageFiles.deleteAll();
        packages.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();

        User alice = seedUser("alice-gov@example.com");
        aliceId = alice.getId();
        aliceToken = jwtService.generateToken(alice);
        aliceProject = workspaceRepository.save(Workspace.builder()
                .userId(aliceId).name("web").build()).getId();

        User bob = seedUser("bob-gov@example.com");
        bobToken = jwtService.generateToken(bob);

        publishedId = packages.save(GovernancePackage.builder()
                .slug("livrables").name("Livrables sans trace").version(2).published(true)
                .rules("Aucun livrable ne doit suggérer un LLM.").build()).getId();
        draftId = packages.save(GovernancePackage.builder()
                .slug("brouillon").name("Brouillon").version(1).published(false)
                .rules("Rien encore.").build()).getId();
    }

    private User seedUser(String email) {
        // ADMIN : l'accès Atelier (F-40) est accordé aux admins sans abonnement, ce qui évite de
        // monter une souscription pour tester la gouvernance.
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }

    @Test
    @DisplayName("retenir, activer, lire l'état du projet, désactiver")
    void fullJourney() throws Exception {
        mockMvc.perform(put("/api/governance/selection/" + publishedId).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"defaultApplied\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].defaultApplied").value(true))
                .andExpect(jsonPath("$[0].activeProjects").value(0))
                .andExpect(jsonPath("$[0].pkg.slug").value("livrables"));

        mockMvc.perform(post("/api/workspaces/" + aliceProject + "/governance/" + publishedId)
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.active[0].appliedVersion").value(2))
                .andExpect(jsonPath("$.active[0].outdated").value(false))
                .andExpect(jsonPath("$.active[0].status").value("PENDING"))
                .andExpect(jsonPath("$.available", Matchers.hasSize(0)));

        // Rejouer l'activation n'invente pas une seconde ligne.
        mockMvc.perform(post("/api/workspaces/" + aliceProject + "/governance/" + publishedId)
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", Matchers.hasSize(1)));
        assertThat(activations.findAll()).hasSize(1);

        mockMvc.perform(get("/api/workspaces/" + aliceProject + "/governance").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workspaceId").value(aliceProject.toString()))
                .andExpect(jsonPath("$.active[0].pkg.name").value("Livrables sans trace"));

        mockMvc.perform(delete("/api/workspaces/" + aliceProject + "/governance/" + publishedId)
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
        // Idempotent : désactiver deux fois reste un succès.
        mockMvc.perform(delete("/api/workspaces/" + aliceProject + "/governance/" + publishedId)
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
        assertThat(activations.findAll()).isEmpty();
        // Le paquet reste dans mon catalogue : désactiver n'est pas oublier.
        assertThat(selections.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("on n'active que ce qu'on a retenu")
    void cannotActivateWithoutSelecting() throws Exception {
        mockMvc.perform(post("/api/workspaces/" + aliceProject + "/governance/" + publishedId)
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("governance_conflict"));
        assertThat(activations.findAll()).isEmpty();
    }

    @Test
    @DisplayName("un brouillon n'existe pas : on ne peut pas le retenir")
    void cannotSelectDraft() throws Exception {
        mockMvc.perform(put("/api/governance/selection/" + draftId).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        assertThat(selections.findAll()).isEmpty();
    }

    @Test
    @DisplayName("le catalogue d'un autre compte est invisible, et son projet inatteignable")
    void nothingCrossesAccounts() throws Exception {
        mockMvc.perform(put("/api/governance/selection/" + publishedId).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        // Bob : catalogue vide, malgré la sélection d'Alice.
        mockMvc.perform(get("/api/governance/selection").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(0)));

        // Bob : le projet d'Alice est « introuvable », jamais « interdit ».
        mockMvc.perform(get("/api/workspaces/" + aliceProject + "/governance").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());

        // Et une tentative d'activation n'écrit RIEN.
        mockMvc.perform(post("/api/workspaces/" + aliceProject + "/governance/" + publishedId)
                        .contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        assertThat(activations.findAll()).isEmpty();
    }

    @Test
    @DisplayName("retirer de son catalogue laisse vivre les projets qui l'appliquent")
    void deselectLeavesActivationsAlone() throws Exception {
        mockMvc.perform(put("/api/governance/selection/" + publishedId).contextPath("/api")
                .header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        mockMvc.perform(post("/api/workspaces/" + aliceProject + "/governance/" + publishedId)
                .contextPath("/api").header("Authorization", "Bearer " + aliceToken));

        mockMvc.perform(delete("/api/governance/selection/" + publishedId).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        assertThat(selections.findAll()).isEmpty();
        assertThat(activations.findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(aliceId, aliceProject))
                .hasSize(1);
    }

    @Test
    @DisplayName("le catalogue personnel exige d'être connecté")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/governance/selection").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }
}
