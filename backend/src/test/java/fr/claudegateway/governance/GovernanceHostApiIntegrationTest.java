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
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-51 / SF-51-02 et F-75 / SF-75-01 — le catalogue personnel et l'activation <b>par poste</b>, de
 * bout en bout.
 *
 * <p>Ce que ce test protège avant tout : <b>rien ne traverse un compte</b>. Bob ne voit pas le
 * catalogue d'Alice, ne lit pas la gouvernance de son poste, et ne peut rien y activer — et surtout,
 * une tentative n'écrit <b>aucune ligne</b>.</p>
 *
 * <p>Il protège aussi le <b>grain</b> : il n'existe plus aucune route qui activerait une gouvernance
 * sur un dossier seul. C'est la décision du PO — aucune dérogation par dossier — et elle se vérifie
 * par l'absence de la route, pas par une promesse.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GovernanceHostApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hosts;
    @Autowired private GovernancePackageRepository packages;
    @Autowired private GovernancePackageFileRepository packageFiles;
    @Autowired private GovernanceSelectionRepository selections;
    @Autowired private GovernanceActivationRepository activations;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private UUID aliceId;
    private UUID aliceHost;
    private UUID publishedId;
    private UUID draftId;

    @BeforeEach
    void setUp() {
        activations.deleteAll();
        selections.deleteAll();
        packageFiles.deleteAll();
        packages.deleteAll();
        workspaceRepository.deleteAll();
        hosts.deleteAll();
        userRepository.deleteAll();

        User alice = seedUser("alice-gov@example.com");
        aliceId = alice.getId();
        aliceToken = jwtService.generateToken(alice);
        aliceHost = hosts.save(RunnerHost.builder().userId(aliceId).name("EDENRED").build()).getId();
        workspaceRepository.save(Workspace.builder()
                .userId(aliceId).name("web").hostId(aliceHost).build());

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

    private String hostPath(UUID hostId) {
        return "/api/governance/hosts/" + hostId;
    }

    @Test
    @DisplayName("retenir, activer sur un poste, lire son état, désactiver")
    void fullJourney() throws Exception {
        mockMvc.perform(put("/api/governance/selection/" + publishedId).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"defaultApplied\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].defaultApplied").value(true))
                .andExpect(jsonPath("$[0].pkg.slug").value("livrables"));

        mockMvc.perform(post(hostPath(aliceHost) + "/" + publishedId)
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("EDENRED"))
                .andExpect(jsonPath("$.active", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.active[0].appliedVersion").value(2))
                .andExpect(jsonPath("$.active[0].outdated").value(false))
                // APPLIED : l'activation dépose dans la foulée, et ce paquet n'apporte aucun
                // fichier — il n'y a donc rien à attendre.
                .andExpect(jsonPath("$.active[0].status").value("APPLIED"))
                .andExpect(jsonPath("$.available", Matchers.hasSize(0)))
                // Le dossier du poste est nommé : c'est LUI qui recevra les fichiers.
                .andExpect(jsonPath("$.projects", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.projects[0].name").value("web"));

        // Rejouer l'activation n'invente pas une seconde ligne.
        mockMvc.perform(post(hostPath(aliceHost) + "/" + publishedId)
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", Matchers.hasSize(1)));
        assertThat(activations.findAll()).hasSize(1);

        mockMvc.perform(get(hostPath(aliceHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ref").value(aliceHost.toString()))
                .andExpect(jsonPath("$.active[0].pkg.name").value("Livrables sans trace"));

        mockMvc.perform(delete(hostPath(aliceHost) + "/" + publishedId)
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
        // Idempotent : désactiver deux fois reste un succès.
        mockMvc.perform(delete(hostPath(aliceHost) + "/" + publishedId)
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
        assertThat(activations.findAll()).isEmpty();
        // Le paquet reste dans mon catalogue : désactiver n'est pas oublier.
        assertThat(selections.findAll()).hasSize(1);
    }

    @Test
    @DisplayName("un dossier ne s'active pas : la route par projet n'existe plus")
    void thereIsNoPerProjectActivation() throws Exception {
        UUID project = workspaceRepository.findAll().get(0).getId();

        // Aucun handler ne répond plus sur ce chemin. On ne teste pas un code précis : une route
        // absente est traitée par le filet général de la gateway, et c'est son ABSENCE qui compte.
        mockMvc.perform(post("/api/workspaces/" + project + "/governance/" + publishedId)
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(result ->
                        assertThat(result.getResponse().getStatus()).isNotEqualTo(200));
        assertThat(activations.findAll()).isEmpty();
    }

    @Test
    @DisplayName("le poste « Hébergé » s'active par son mot réservé, et n'a pas d'identifiant")
    void hostedIsGovernedByItsReservedWord() throws Exception {
        workspaceRepository.save(Workspace.builder().userId(aliceId).name("archive").build());
        mockMvc.perform(put("/api/governance/selection/" + publishedId).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/governance/hosts/hosted/" + publishedId).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ref").value("hosted"))
                .andExpect(jsonPath("$.virtual").value(true))
                // Décision F-71 : ce poste est une VUE ; aucun identifiant ne sort jamais.
                .andExpect(jsonPath("$.id").doesNotExist())
                .andExpect(jsonPath("$.projects", Matchers.hasSize(1)));

        // La clé technique du poste virtuel n'est PAS une adresse : elle est refusée comme telle.
        mockMvc.perform(get("/api/governance/hosts/00000000-0000-0000-0000-000000000000")
                        .contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("mes postes gouvernables sont listés, avec leurs dossiers")
    void listsGovernableHosts() throws Exception {
        mockMvc.perform(get("/api/governance/hosts").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].name").value("EDENRED"))
                .andExpect(jsonPath("$[0].projects").value(1))
                .andExpect(jsonPath("$[0].active").value(0));
    }

    @Test
    @DisplayName("on n'active que ce qu'on a retenu")
    void cannotActivateWithoutSelecting() throws Exception {
        mockMvc.perform(post(hostPath(aliceHost) + "/" + publishedId)
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
    @DisplayName("une référence de poste qui n'en est pas une est introuvable")
    void nonsenseHostRefIsNotFound() throws Exception {
        mockMvc.perform(get("/api/governance/hosts/tout-le-monde").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("le catalogue d'un autre compte est invisible, et son poste inatteignable")
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

        // Bob : aucun poste gouvernable, et celui d'Alice est « introuvable », jamais « interdit ».
        mockMvc.perform(get("/api/governance/hosts").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(0)));
        mockMvc.perform(get(hostPath(aliceHost)).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());

        // Et une tentative d'activation n'écrit RIEN.
        mockMvc.perform(post(hostPath(aliceHost) + "/" + publishedId)
                        .contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        assertThat(activations.findAll()).isEmpty();
    }

    @Test
    @DisplayName("retirer de son catalogue laisse vivre les postes qui l'appliquent")
    void deselectLeavesActivationsAlone() throws Exception {
        mockMvc.perform(put("/api/governance/selection/" + publishedId).contextPath("/api")
                .header("Authorization", "Bearer " + aliceToken)
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
        mockMvc.perform(post(hostPath(aliceHost) + "/" + publishedId)
                .contextPath("/api").header("Authorization", "Bearer " + aliceToken));

        mockMvc.perform(delete("/api/governance/selection/" + publishedId).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        assertThat(selections.findAll()).isEmpty();
        assertThat(activations.findByUserIdAndHostIdOrderByCreatedAtAsc(aliceId, aliceHost))
                .hasSize(1);
    }

    @Test
    @DisplayName("la gouvernance exige d'être connecté")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/governance/hosts").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }
}
