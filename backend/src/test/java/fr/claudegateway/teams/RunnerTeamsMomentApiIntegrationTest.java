package fr.claudegateway.teams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceSource;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.runner.RunnerTokenRepository;
import fr.claudegateway.runner.RunnerTokenService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.teams.block.TeamsMomentImageService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>Ce qui remonte d'un enregistrement</b> (F-90 / SF-90-03) : la route de dépôt des captures.
 *
 * <p>Quatre choses s'y jouent, et aucune n'est optionnelle : le <b>jeton runner</b> authentifie
 * (401 générique sinon), l'<b>isolation</b> tient — le terminal d'un autre compte est
 * <b>introuvable</b>, indiscernable d'un terminal inexistant —, le <b>droit Teams</b> est exigé pour
 * <b>produire</b> (D5), et rien n'est jamais posé dans le {@code SecurityContext} (D9).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerTeamsMomentApiIntegrationTest {

    private static final String URL = "/api/runner/teams/moments";
    private static final String HEADER = "X-Runner-Token";
    private static final byte[] PIXEL = "png-bytes".getBytes(StandardCharsets.UTF_8);

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private RunnerTokenRepository runnerTokenRepository;
    @Autowired private RunnerTokenService tokenService;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private TeamsMomentImageService images;

    private final ObjectMapper mapper = new ObjectMapper();

    private UUID aliceId;
    private String aliceToken;
    private UUID aliceTerminal;
    private UUID aliceProject;

    private String bobToken;
    private UUID bobTerminal;

    private String carolToken;
    private UUID carolTerminal;

    @BeforeEach
    void setUp() {
        runnerTokenRepository.deleteAll();
        workspaceRepository.deleteAll();
        hostRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        SecurityContextHolder.clearContext();

        User alice = seedUser("alice-captures@example.com");
        aliceId = alice.getId();
        entitle(aliceId);
        RunnerHost aliceHost = seedHost(aliceId);
        aliceToken = tokenService.issue(aliceId, aliceHost.getId(), "poste-alice").clearToken();
        aliceTerminal = seedTerminal(aliceId, true);
        aliceProject = seedTerminal(aliceId, false);

        User bob = seedUser("bob-captures@example.com");
        entitle(bob.getId());
        RunnerHost bobHost = seedHost(bob.getId());
        bobToken = tokenService.issue(bob.getId(), bobHost.getId(), "poste-bob").clearToken();
        bobTerminal = seedTerminal(bob.getId(), true);

        // Carol a un terminal Teams mais PAS l'option : elle peut relire, pas produire.
        User carol = seedUser("carol-captures@example.com");
        RunnerHost carolHost = seedHost(carol.getId());
        carolToken = tokenService.issue(carol.getId(), carolHost.getId(), "poste-carol").clearToken();
        carolTerminal = seedTerminal(carol.getId(), true);
    }

    // ---------------------------------------------------------------- nominal

    @Test
    @DisplayName("une image retenue remonte et rend son identifiant")
    void theImageIsStored() throws Exception {
        MvcResult result = mockMvc.perform(post(URL).contextPath("/api")
                        .param("workspaceId", aliceTerminal.toString())
                        .header(HEADER, aliceToken)
                        .contentType("image/png")
                        .content(PIXEL))
                .andExpect(status().isOk())
                .andReturn();

        String imageId = mapper.readTree(result.getResponse().getContentAsString())
                .path("imageId").asText();
        assertThat(imageId).isNotBlank();
        assertThat(images.exists(aliceId, aliceTerminal, imageId)).isTrue();
    }

    @Test
    @DisplayName("le type voyage avec ses paramètres sans gêner la liste close")
    void theContentTypeParametersAreIgnored() throws Exception {
        mockMvc.perform(post(URL).contextPath("/api")
                        .param("workspaceId", aliceTerminal.toString())
                        .header(HEADER, aliceToken)
                        .contentType("image/jpeg;charset=UTF-8")
                        .content(PIXEL))
                .andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- authentification

    @Test
    @DisplayName("sans jeton runner : 401 générique")
    void withoutTokenItIsUnauthorized() throws Exception {
        mockMvc.perform(post(URL).contextPath("/api")
                        .param("workspaceId", aliceTerminal.toString())
                        .contentType("image/png").content(PIXEL))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("jeton inconnu : le même 401, sans dire pourquoi")
    void withAnUnknownTokenItIsUnauthorizedTheSameWay() throws Exception {
        mockMvc.perform(post(URL).contextPath("/api")
                        .param("workspaceId", aliceTerminal.toString())
                        .header(HEADER, "ceci-n-est-pas-un-jeton")
                        .contentType("image/png").content(PIXEL))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Jeton runner refusé."));
    }

    @Test
    @DisplayName("aucun AuthenticatedUser n'est posé dans le SecurityContext (D9)")
    void noUserPrincipalIsEverSet() throws Exception {
        mockMvc.perform(post(URL).contextPath("/api")
                        .param("workspaceId", aliceTerminal.toString())
                        .header(HEADER, aliceToken)
                        .contentType("image/png").content(PIXEL))
                .andExpect(status().isOk());

        assertThat(SecurityContextHolder.getContext().getAuthentication())
                .as("un jeton runner n'est pas un JWT utilisateur : il n'authentifie jamais un "
                        + "endpoint utilisateur")
                .isNull();
    }

    // ---------------------------------------------------------------- isolation

    @Test
    @DisplayName("ISOLATION : le terminal d'un autre compte est introuvable")
    void anotherAccountsTerminalIsNotFound() throws Exception {
        mockMvc.perform(post(URL).contextPath("/api")
                        .param("workspaceId", bobTerminal.toString())
                        .header(HEADER, aliceToken)
                        .contentType("image/png").content(PIXEL))
                .andExpect(status().isNotFound());

        assertThat(images.list(aliceId, bobTerminal)).isEmpty();
    }

    @Test
    @DisplayName("un terminal inexistant rend le MÊME 404 : indiscernables")
    void anUnknownTerminalIsNotFoundTheSameWay() throws Exception {
        mockMvc.perform(post(URL).contextPath("/api")
                        .param("workspaceId", UUID.randomUUID().toString())
                        .header(HEADER, aliceToken)
                        .contentType("image/png").content(PIXEL))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Terminal introuvable."));
    }

    @Test
    @DisplayName("l'image de Bob monte dans le terminal de Bob, pas dans celui d'Alice")
    void eachRunnerWritesUnderItsOwnAccount() throws Exception {
        mockMvc.perform(post(URL).contextPath("/api")
                        .param("workspaceId", bobTerminal.toString())
                        .header(HEADER, bobToken)
                        .contentType("image/png").content(PIXEL))
                .andExpect(status().isOk());

        assertThat(images.list(aliceId, aliceTerminal)).isEmpty();
    }

    // ---------------------------------------------------------------- droit et nature

    @Test
    @DisplayName("D5 : sans l'option Teams, produire est refusé")
    void withoutTheTeamsOptionItIsForbidden() throws Exception {
        mockMvc.perform(post(URL).contextPath("/api")
                        .param("workspaceId", carolTerminal.toString())
                        .header(HEADER, carolToken)
                        .contentType("image/png").content(PIXEL))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("option Teams")));
    }

    @Test
    @DisplayName("on ne dépose pas de captures de réunion dans un projet de code")
    void aProjectWorkspaceIsRefused() throws Exception {
        mockMvc.perform(post(URL).contextPath("/api")
                        .param("workspaceId", aliceProject.toString())
                        .header(HEADER, aliceToken)
                        .contentType("image/png").content(PIXEL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("terminal Teams")));
    }

    // ---------------------------------------------------------------- bornes, toutes nommées

    @Test
    @DisplayName("un type hors de la liste close est refusé, et nommé")
    void anUnacceptedTypeIsRefused() throws Exception {
        mockMvc.perform(post(URL).contextPath("/api")
                        .param("workspaceId", aliceTerminal.toString())
                        .header(HEADER, aliceToken)
                        .contentType("image/gif").content(PIXEL))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("image/gif")));
    }

    @Test
    @DisplayName("une image vide est refusée")
    void anEmptyImageIsRefused() throws Exception {
        mockMvc.perform(post(URL).contextPath("/api")
                        .param("workspaceId", aliceTerminal.toString())
                        .header(HEADER, aliceToken)
                        .contentType("image/png").content(new byte[0]))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("une image trop lourde est refusée, et la borne est dite")
    void anOversizedImageIsRefused() throws Exception {
        mockMvc.perform(post(URL).contextPath("/api")
                        .param("workspaceId", aliceTerminal.toString())
                        .header(HEADER, aliceToken)
                        .contentType("image/png")
                        .content(new byte[RunnerTeamsMomentController.MAX_IMAGE_BYTES + 1]))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString(
                                String.valueOf(RunnerTeamsMomentController.MAX_IMAGE_BYTES))));
    }

    @Test
    @DisplayName("au-delà du plafond par terminal, le refus est nommé")
    void beyondTheWorkspaceCapItIsRefused() throws Exception {
        // Sans borne, une machine pourrait remplir le stockage d'un compte. Le plafond est posé
        // ici pour de vrai, pas simulé : c'est la seule façon de vérifier qu'il mord.
        for (int index = 0; index < RunnerTeamsMomentController.MAX_IMAGES_PER_WORKSPACE; index++) {
            images.store(aliceId, aliceTerminal, "image/png", PIXEL);
        }

        mockMvc.perform(post(URL).contextPath("/api")
                        .param("workspaceId", aliceTerminal.toString())
                        .header(HEADER, aliceToken)
                        .contentType("image/png").content(PIXEL))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        org.hamcrest.Matchers.containsString("supprimez un compte rendu")));
    }

    // ---------------------------------------------------------------- montage

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
    }

    /** L'option Teams, souscrite pour de vrai : aucun bouchon dans un test d'intégration. */
    private void entitle(UUID userId) {
        subscriptionRepository.save(Subscription.builder()
                .userId(userId)
                .planCode(PlanCode.PRO)
                .status(SubscriptionStatus.ACTIVE)
                .teamsOptionStatus(SubscriptionStatus.ACTIVE)
                .build());
    }

    private RunnerHost seedHost(UUID userId) {
        return hostRepository.save(RunnerHost.builder().userId(userId).name("Poste").build());
    }

    private UUID seedTerminal(UUID userId, boolean teamsTerminal) {
        return workspaceRepository.save(Workspace.builder()
                .userId(userId)
                .name(teamsTerminal ? "Terminal Teams" : "Projet de code")
                .projectPath("")
                .source(WorkspaceSource.LOCAL)
                .teamsTerminal(teamsTerminal)
                .build()).getId();
    }
}
