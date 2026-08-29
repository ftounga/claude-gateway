package fr.claudegateway.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests de la mise à jour de l'activité runner (F-38 / SF-38-02) : {@code touch} rafraîchit
 * {@code last_seen_at} en base ; un jeton inconnu est ignoré sans erreur.
 */
@SpringBootTest
@ActiveProfiles("test")
class RunnerHeartbeatServiceTest {

    @Autowired
    private RunnerHeartbeatService heartbeatService;
    @Autowired
    private RunnerTokenService tokenService;
    @Autowired
    private RunnerTokenRepository tokenRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WorkspaceRepository workspaceRepository;

    private UUID userId;
    private UUID workspaceId;

    @BeforeEach
    void setUp() {
        tokenRepository.deleteAll();
        workspaceRepository.deleteAll();
        userRepository.deleteAll();
        User user = userRepository.save(User.builder()
                .email("hb@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        userId = user.getId();
        workspaceId = workspaceRepository.save(
                Workspace.builder().userId(userId).name("Projet").build()).getId();
    }

    @Test
    void touchUpdatesLastSeenAt() {
        RunnerTokenService.IssuedToken issued = tokenService.issue(userId, workspaceId, null);
        UUID tokenId = issued.token().getId();
        assertThat(tokenRepository.findById(tokenId).orElseThrow().getLastSeenAt()).isNull();

        OffsetDateTime before = OffsetDateTime.now().minusSeconds(1);
        heartbeatService.touch(tokenId);

        OffsetDateTime lastSeen = tokenRepository.findById(tokenId).orElseThrow().getLastSeenAt();
        assertThat(lastSeen).isNotNull().isAfter(before);
    }

    @Test
    void touchOfUnknownTokenIsNoOp() {
        heartbeatService.touch(UUID.randomUUID()); // ne doit pas lever
    }
}
