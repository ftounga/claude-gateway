package fr.claudegateway.runner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.audit.RunnerAudit;
import fr.claudegateway.runner.audit.RunnerAuditOutcome;
import fr.claudegateway.runner.audit.RunnerAuditRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Suppression de compte et domaine runner (F-38 / SF-38-14) : jetons, codes d'appairage et journal
 * d'audit disparaissent avec le compte, le jeton cesse d'authentifier, et les données d'un autre
 * utilisateur restent strictement intactes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RunnerAccountDeletionApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;
    @Autowired
    private RunnerTokenRepository tokenRepository;
    @Autowired
    private RunnerPairingCodeRepository pairingCodeRepository;
    @Autowired
    private RunnerAuditRepository auditRepository;
    @Autowired
    private RunnerTokenService tokenService;
    @Autowired
    private RunnerPairingService pairingService;
    @Autowired
    private JwtService jwtService;

    private User owner;
    private String ownerJwt;
    private UUID ownerWorkspaceId;
    private String ownerClearToken;

    private User other;
    private UUID otherWorkspaceId;
    private String otherClearToken;

    @BeforeEach
    void setUp() {
        auditRepository.deleteAll();
        tokenRepository.deleteAll();
        pairingCodeRepository.deleteAll();

        owner = userRepository.save(user("runner-purge-owner-" + UUID.randomUUID() + "@example.com"));
        ownerJwt = jwtService.generateToken(owner);
        ownerWorkspaceId = workspaceRepository.save(workspace(owner.getId())).getId();
        ownerClearToken = seed(owner.getId(), ownerWorkspaceId);

        other = userRepository.save(user("runner-purge-other-" + UUID.randomUUID() + "@example.com"));
        otherWorkspaceId = workspaceRepository.save(workspace(other.getId())).getId();
        otherClearToken = seed(other.getId(), otherWorkspaceId);
    }

    @Test
    void deletingTheAccountRemovesEveryRunnerRowOfThatUser() throws Exception {
        assertThat(tokenRepository.findAll()).hasSize(2);

        mockMvc.perform(delete("/api/account").contextPath("/api")
                        .header("Authorization", "Bearer " + ownerJwt))
                .andExpect(status().isOk());

        assertThat(tokenRepository.findAll())
                .as("seul le jeton de l'autre utilisateur subsiste")
                .extracting(RunnerToken::getUserId)
                .containsExactly(other.getId());
        assertThat(pairingCodeRepository.findAll())
                .extracting(RunnerPairingCode::getUserId)
                .containsExactly(other.getId());
        assertThat(auditRepository.findAll())
                .extracting(RunnerAudit::getUserId)
                .containsExactly(other.getId());
    }

    @Test
    void aTokenOfADeletedAccountNoLongerAuthenticates() throws Exception {
        // `/runner/disconnect` répond immédiatement — contrairement à `/runner/poll`, qui tiendrait
        // la requête 25 s. C'est bien l'authentification qu'on teste ici, pas le transport.
        mockMvc.perform(post("/api/runner/disconnect").contextPath("/api")
                        .header(RunnerPollController.TOKEN_HEADER, ownerClearToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/account").contextPath("/api")
                        .header("Authorization", "Bearer " + ownerJwt))
                .andExpect(status().isOk());

        // Après suppression : refus générique, rien qui distingue « compte supprimé » d'un jeton faux.
        mockMvc.perform(post("/api/runner/disconnect").contextPath("/api")
                        .header(RunnerPollController.TOKEN_HEADER, ownerClearToken))
                .andExpect(status().isUnauthorized());

        // Le jeton de l'autre utilisateur continue d'authentifier : la purge est bien filtrée.
        mockMvc.perform(post("/api/runner/disconnect").contextPath("/api")
                        .header(RunnerPollController.TOKEN_HEADER, otherClearToken))
                .andExpect(status().isNoContent());
    }

    private String seed(UUID userId, UUID workspaceId) {
        String clear = tokenService.issue(userId, workspaceId, "poste").clearToken();
        pairingService.createPairingCode(userId, workspaceId);
        auditRepository.save(RunnerAudit.builder()
                .userId(userId).workspaceId(workspaceId)
                .callId("toolu_" + UUID.randomUUID()).tool("bash").target("echo ok")
                .outcome(RunnerAuditOutcome.OK.name()).createdAt(OffsetDateTime.now())
                .build());
        return clear;
    }

    private static User user(String email) {
        return User.builder().email(email).emailVerified(true).provider(AuthProvider.LOCAL)
                .role(UserRole.ADMIN).createdAt(OffsetDateTime.now()).build();
    }

    private static Workspace workspace(UUID userId) {
        return Workspace.builder().userId(userId).name("projet").createdAt(OffsetDateTime.now()).build();
    }
}
