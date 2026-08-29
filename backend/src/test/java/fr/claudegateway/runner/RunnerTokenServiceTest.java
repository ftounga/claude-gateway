package fr.claudegateway.runner;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Optional;
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
 * Tests du cycle de vie des jetons runner et de leur vérification (F-38 / SF-38-01) : stockage
 * haché, émission, et {@link RunnerTokenAuthenticator} sur jeton valide / expiré / révoqué.
 */
@SpringBootTest
@ActiveProfiles("test")
class RunnerTokenServiceTest {

    @Autowired
    private RunnerTokenService tokenService;
    @Autowired
    private RunnerTokenAuthenticator authenticator;
    @Autowired
    private RunnerTokenRepository tokenRepository;
    @Autowired
    private TokenHasher tokenHasher;
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
                .email("u@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        userId = user.getId();
        workspaceId = workspaceRepository.save(
                Workspace.builder().userId(userId).name("Projet").build()).getId();
    }

    @Test
    void issuedTokenIsStoredHashedNotInClear() {
        RunnerTokenService.IssuedToken issued = tokenService.issue(userId, workspaceId, "poste");
        assertThat(issued.clearToken()).isNotBlank();
        assertThat(issued.token().getTokenHash())
                .isEqualTo(tokenHasher.sha256Hex(issued.clearToken()))
                .isNotEqualTo(issued.clearToken());
        assertThat(tokenRepository.findByTokenHash(tokenHasher.sha256Hex(issued.clearToken()))).isPresent();
    }

    @Test
    void authenticatorResolvesValidToken() {
        RunnerTokenService.IssuedToken issued = tokenService.issue(userId, workspaceId, null);
        Optional<RunnerIdentity> identity = authenticator.authenticate(issued.clearToken());
        assertThat(identity).isPresent();
        assertThat(identity.get().userId()).isEqualTo(userId);
        assertThat(identity.get().workspaceId()).isEqualTo(workspaceId);
    }

    @Test
    void authenticatorRejectsUnknownToken() {
        assertThat(authenticator.authenticate("inconnu")).isEmpty();
        assertThat(authenticator.authenticate(null)).isEmpty();
    }

    @Test
    void authenticatorRejectsExpiredToken() {
        String clear = "clef-expiree";
        tokenRepository.save(RunnerToken.builder()
                .userId(userId).workspaceId(workspaceId)
                .tokenHash(tokenHasher.sha256Hex(clear))
                .expiresAt(OffsetDateTime.now().minusSeconds(1))
                .build());
        assertThat(authenticator.authenticate(clear)).isEmpty();
    }

    @Test
    void authenticatorRejectsRevokedToken() {
        RunnerTokenService.IssuedToken issued = tokenService.issue(userId, workspaceId, null);
        tokenService.revoke(userId, workspaceId, issued.token().getId());
        assertThat(authenticator.authenticate(issued.clearToken())).isEmpty();
    }

    @Test
    void revokeIsIdempotent() {
        RunnerTokenService.IssuedToken issued = tokenService.issue(userId, workspaceId, null);
        UUID tokenId = issued.token().getId();
        tokenService.revoke(userId, workspaceId, tokenId);
        OffsetDateTime firstRevokedAt = tokenRepository.findById(tokenId).orElseThrow().getRevokedAt();
        tokenService.revoke(userId, workspaceId, tokenId);
        assertThat(tokenRepository.findById(tokenId).orElseThrow().getRevokedAt()).isEqualTo(firstRevokedAt);
    }
}
