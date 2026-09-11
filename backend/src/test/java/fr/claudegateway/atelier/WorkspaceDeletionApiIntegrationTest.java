package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.quota.UsageTurn;
import fr.claudegateway.quota.UsageTurnRepository;
import fr.claudegateway.runner.RunnerToken;
import fr.claudegateway.runner.RunnerTokenRepository;
import fr.claudegateway.runner.audit.RunnerAudit;
import fr.claudegateway.runner.audit.RunnerAuditRepository;
import fr.claudegateway.runner.channel.RunnerCallDispatcher;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Ce que <b>supprimer un projet</b> efface, et surtout ce qu'il n'efface pas (F-69 / SF-69-01).
 *
 * <p>Le geste existait depuis F-28 sans jamais être exposé à l'écran. Avant de poser le bouton, le
 * PO a demandé de vérifier ce que ce chemin fait <b>réellement</b> — SF-11-03 y avait ajouté une
 * purge large à la suppression de compte. Ce test est la réponse, et il la fige :</p>
 *
 * <ul>
 *   <li>tout ce qui est <b>côté gateway</b> part — conversation, journal, fichiers du stockage ;</li>
 *   <li><b>rien ne part chez l'utilisateur</b> : aucun appel n'atteint le canal du runner, donc
 *       aucune commande, donc aucun dossier touché — y compris sur un projet en cible
 *       {@code RUNNER} dont le {@code project_path} désigne un vrai dossier de la machine ;</li>
 *   <li>le <b>poste</b> et ses jetons survivent : supprimer un projet n'est pas débrancher une
 *       machine ;</li>
 *   <li>le <b>relevé de consommation</b> (F-61) survit : la dépense a eu lieu.</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WorkspaceDeletionApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private AtelierMessageRepository messageRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private RunnerTokenRepository tokenRepository;
    @Autowired private RunnerAuditRepository auditRepository;
    @Autowired private UsageTurnRepository usageTurnRepository;
    @Autowired private JwtService jwtService;

    /**
     * Le <b>seul</b> chemin par lequel la gateway parle à la machine de l'utilisateur. L'espionner
     * transforme « le dossier n'est pas touché » d'une lecture de code en un test qui échoue si
     * quelqu'un ajoute un jour un appel au runner dans la suppression.
     */
    @MockitoSpyBean private RunnerCallDispatcher runnerCalls;

    private String aliceToken;
    private String bobToken;
    private RunnerHost aliceHost;
    private Workspace aliceProject;
    private Workspace aliceOtherProject;
    private Workspace bobProject;

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
        usageTurnRepository.deleteAll();
        messageRepository.deleteAll();
        tokenRepository.deleteAll();
        workspaceRepository.deleteAll();
        hostRepository.deleteAll();
        userRepository.deleteAll();

        User alice = seedUser("alice-delete@example.com");
        aliceToken = jwtService.generateToken(alice);
        aliceHost = seedHost(alice.getId(), "Poste CAGIP");
        aliceProject = seedProject(alice.getId(), aliceHost.getId(), "client-cagip");
        aliceOtherProject = seedProject(alice.getId(), aliceHost.getId(), "client-edenred");
        seedToken(alice.getId(), aliceHost.getId());
        seedAudit(alice.getId(), aliceHost.getId(), aliceProject.getId());
        seedAudit(alice.getId(), aliceHost.getId(), aliceOtherProject.getId());
        seedMessage(alice.getId(), aliceProject.getId());
        seedMessage(alice.getId(), aliceOtherProject.getId());
        seedUsageTurn(alice.getId(), aliceHost.getId(), aliceProject.getId());

        User bob = seedUser("bob-delete@example.com");
        bobToken = jwtService.generateToken(bob);
        RunnerHost bobHost = seedHost(bob.getId(), "Poste de Bob");
        bobProject = seedProject(bob.getId(), bobHost.getId(), "client-de-bob");
        seedAudit(bob.getId(), bobHost.getId(), bobProject.getId());
    }

    private String url(UUID workspaceId) {
        return "/api/workspaces/" + workspaceId;
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }

    private RunnerHost seedHost(UUID userId, String name) {
        return hostRepository.save(RunnerHost.builder().userId(userId).name(name).rootName("dev")
                .os("linux").shell("posix").elevated(false)
                .lastSeenAt(OffsetDateTime.now()).build());
    }

    /** Un projet qui vit sur la machine : cible {@code RUNNER}, et un dossier réel sous la racine. */
    private Workspace seedProject(UUID userId, UUID hostId, String folder) {
        return workspaceRepository.save(Workspace.builder().userId(userId).name(folder)
                .hostId(hostId).projectPath(folder)
                .executionTarget(WorkspaceExecutionTarget.RUNNER).build());
    }

    private void seedToken(UUID userId, UUID hostId) {
        tokenRepository.save(RunnerToken.builder().userId(userId).hostId(hostId)
                .tokenHash("hash-" + UUID.randomUUID()).label("poste")
                .expiresAt(OffsetDateTime.now().plusDays(30)).build());
    }

    private void seedAudit(UUID userId, UUID hostId, UUID workspaceId) {
        auditRepository.save(RunnerAudit.builder().userId(userId).hostId(hostId)
                .workspaceId(workspaceId).callId(UUID.randomUUID().toString()).tool("bash")
                .target("cible").outcome("OK").createdAt(OffsetDateTime.now()).build());
    }

    private void seedMessage(UUID userId, UUID workspaceId) {
        messageRepository.save(AtelierMessage.builder().userId(userId).workspaceId(workspaceId)
                .role("user").content("bonjour").build());
    }

    private void seedUsageTurn(UUID userId, UUID hostId, UUID workspaceId) {
        usageTurnRepository.save(UsageTurn.builder().userId(userId).hostId(hostId)
                .workspaceId(workspaceId).inputTokens(1_000L).outputTokens(500L)
                .occurredAt(OffsetDateTime.now()).build());
    }

    private long auditFor(UUID workspaceId) {
        return auditRepository.findAll().stream()
                .filter(entry -> workspaceId.equals(entry.getWorkspaceId()))
                .count();
    }

    private long messagesFor(UUID workspaceId) {
        return messageRepository.findAll().stream()
                .filter(message -> workspaceId.equals(message.getWorkspaceId()))
                .count();
    }

    // ------------------------------------------------------------------ cas nominal

    @Test
    void deletingAProjectErasesItsConversationAndItsJournal() throws Exception {
        mockMvc.perform(delete(url(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        assertThat(workspaceRepository.findById(aliceProject.getId())).isEmpty();
        assertThat(messagesFor(aliceProject.getId())).isZero();
        // Le journal part avec le projet : il porte des commandes exécutées et des chemins lus, et
        // sa seule lecture — GET /workspaces/{id}/runner/audit — n'existe plus.
        assertThat(auditFor(aliceProject.getId())).isZero();
    }

    @Test
    void deletingAProjectNeverReachesTheUsersMachine() throws Exception {
        mockMvc.perform(delete(url(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        // Le cœur de la décision du PO : le dossier sur la machine n'est JAMAIS touché. Il ne peut
        // pas l'être — rien dans ce chemin n'ouvre le canal du runner.
        verifyNoInteractions(runnerCalls);
    }

    @Test
    void deletingAProjectLeavesTheMachinePaired() throws Exception {
        mockMvc.perform(delete(url(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        // Supprimer un projet n'est pas débrancher une machine : le poste et son jeton vivent.
        assertThat(hostRepository.findById(aliceHost.getId())).isPresent();
        assertThat(tokenRepository.findAll())
                .hasSize(1)
                .allSatisfy(token -> assertThat(token.getRevokedAt()).isNull());
    }

    @Test
    void deletingAProjectKeepsTheBillingEvidence() throws Exception {
        mockMvc.perform(delete(url(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        // F-61 : la dépense a eu lieu. L'écran d'usage sait déjà nommer « supprimé » un projet
        // absent ; effacer ces lignes ferait rétrécir une consommation déjà facturée.
        assertThat(usageTurnRepository.count()).isEqualTo(1);
    }

    @Test
    void deletingAProjectLeavesTheNeighbourAlone() throws Exception {
        mockMvc.perform(delete(url(aliceProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        assertThat(workspaceRepository.findById(aliceOtherProject.getId())).isPresent();
        assertThat(messagesFor(aliceOtherProject.getId())).isEqualTo(1);
        assertThat(auditFor(aliceOtherProject.getId())).isEqualTo(1);
    }

    // ------------------------------------------------------------------ isolation

    @Test
    void theProjectOfAnotherAccountCannotBeDeleted() throws Exception {
        // 404 et non 403 : un 403 dirait que le projet existe, ce qui est déjà une information.
        mockMvc.perform(delete(url(bobProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());

        assertThat(workspaceRepository.findById(bobProject.getId())).isPresent();
        assertThat(auditFor(bobProject.getId())).isEqualTo(1);
    }

    @Test
    void bobDeletesHisOwnProject() throws Exception {
        mockMvc.perform(delete(url(bobProject.getId())).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNoContent());

        assertThat(workspaceRepository.findById(bobProject.getId())).isEmpty();
        assertThat(auditFor(bobProject.getId())).isZero();
        // Le voisinage d'Alice n'a pas bougé d'une ligne.
        assertThat(auditFor(aliceProject.getId())).isEqualTo(1);
    }

    @Test
    void anUnknownProjectIsNotFound() throws Exception {
        mockMvc.perform(delete(url(UUID.randomUUID())).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void theGestureIsClosedWithoutJwt() throws Exception {
        mockMvc.perform(delete(url(aliceProject.getId())).contextPath("/api"))
                .andExpect(status().isUnauthorized());

        assertThat(workspaceRepository.findById(aliceProject.getId())).isPresent();
    }
}
