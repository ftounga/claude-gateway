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
 * {@code last_seen_at} en base ; un jeton inconnu est ignoré sans erreur. Depuis F-194 / SF-194-01,
 * {@code touch} repousse aussi l'échéance d'un jeton valide, jamais celle d'un jeton révoqué ou expiré.
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
    private RunnerTokenAuthenticator authenticator;
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

    @Test
    void touchPushesBackTheExpiryOfAValidToken() {
        RunnerTokenService.IssuedToken issued = tokenService.issue(userId, workspaceId, null);
        RunnerToken token = issued.token();
        // Jeton appairé il y a 29 jours : il lui reste un jour.
        token.setExpiresAt(OffsetDateTime.now().plusDays(1));
        tokenRepository.save(token);

        OffsetDateTime before = OffsetDateTime.now();
        heartbeatService.touch(token.getId());

        OffsetDateTime expiresAt = tokenRepository.findById(token.getId()).orElseThrow().getExpiresAt();
        assertThat(expiresAt).isAfter(before.plusDays(29)).isBefore(OffsetDateTime.now().plusDays(30).plusSeconds(1));
    }

    @Test
    void touchNeverExtendsARevokedToken() {
        RunnerTokenService.IssuedToken issued = tokenService.issue(userId, workspaceId, null);
        RunnerToken token = issued.token();
        token.setExpiresAt(OffsetDateTime.now().plusDays(1));
        token.setRevokedAt(OffsetDateTime.now().minusMinutes(1));
        tokenRepository.save(token);

        heartbeatService.touch(token.getId());

        RunnerToken reloaded = tokenRepository.findById(token.getId()).orElseThrow();
        // Toujours l'échéance d'origine (J+1), pas J+30.
        assertThat(reloaded.getExpiresAt()).isBefore(OffsetDateTime.now().plusDays(2));
        assertThat(reloaded.getLastSeenAt()).isNotNull();
    }

    @Test
    void touchNeverRevivesAnExpiredToken() {
        RunnerTokenService.IssuedToken issued = tokenService.issue(userId, workspaceId, null);
        RunnerToken token = issued.token();
        token.setExpiresAt(OffsetDateTime.now().minusMinutes(1));
        tokenRepository.save(token);

        heartbeatService.touch(token.getId());

        assertThat(tokenRepository.findById(token.getId()).orElseThrow().getExpiresAt())
                .isBefore(OffsetDateTime.now());
        assertThat(authenticator.authenticate(issued.clearToken())).isEmpty();
    }
}
