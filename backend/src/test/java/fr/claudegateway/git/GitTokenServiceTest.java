package fr.claudegateway.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.byok.ByokDisabledException;
import fr.claudegateway.byok.ByokKeyCipher;
import fr.claudegateway.byok.EncryptedKey;
import fr.claudegateway.git.dto.GitTokenStatusResponse;

/**
 * Tests unitaires de {@link GitTokenService} : vérification auprès de GitHub <b>avant</b> toute
 * écriture, chiffrement, remplacement, état masqué et retrait idempotent.
 */
@ExtendWith(MockitoExtension.class)
class GitTokenServiceTest {

    private static final String TOKEN = "github_pat_11ABCDE_secretAB12";

    @Mock
    private UserGitCredentialRepository repository;
    @Mock
    private ByokKeyCipher cipher;
    @Mock
    private GitHubClient gitHubClient;

    private final UUID userId = UUID.randomUUID();

    private GitTokenService service() {
        return new GitTokenService(repository, cipher, gitHubClient);
    }

    private static EncryptedKey encrypted() {
        return new EncryptedKey("dk", "iv", "ct");
    }

    @Test
    void savesVerifiedTokenEncryptedAndReturnsMaskedStatus() {
        when(gitHubClient.verifyToken(TOKEN)).thenReturn(new GitHubAccount("octocat"));
        when(cipher.encrypt(TOKEN)).thenReturn(encrypted());
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());
        when(repository.save(any(UserGitCredential.class))).thenAnswer(call -> call.getArgument(0));

        GitTokenStatusResponse status = service().saveToken(userId, TOKEN);

        ArgumentCaptor<UserGitCredential> captor = ArgumentCaptor.forClass(UserGitCredential.class);
        verify(repository).save(captor.capture());
        UserGitCredential saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getGithubLogin()).isEqualTo("octocat");
        assertThat(saved.getTokenLast4()).isEqualTo("AB12");
        // Le jeton en clair n'est stocké nulle part.
        assertThat(saved.getCiphertext()).isEqualTo("ct");
        assertThat(saved.getEncryptedDataKey()).isEqualTo("dk");
        assertThat(saved.getCipherIv()).isEqualTo("iv");

        assertThat(status.present()).isTrue();
        assertThat(status.githubLogin()).isEqualTo("octocat");
        assertThat(status.last4()).isEqualTo("AB12");
        assertThat(status.maskedToken()).isEqualTo("…AB12");
    }

    @Test
    void rejectsBlankTokenWithoutCallingGitHubOrPersisting() {
        assertThatThrownBy(() -> service().saveToken(userId, "   "))
                .isInstanceOf(InvalidGitTokenException.class);

        verifyNoInteractions(gitHubClient);
        verifyNoInteractions(cipher);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsOverlongTokenWithoutCallingGitHub() {
        assertThatThrownBy(() -> service().saveToken(userId, "g".repeat(256)))
                .isInstanceOf(InvalidGitTokenException.class);

        verifyNoInteractions(gitHubClient);
        verifyNoInteractions(repository);
    }

    @Test
    void doesNotPersistWhenGitHubRefusesTheToken() {
        when(gitHubClient.verifyToken(TOKEN)).thenThrow(new InvalidGitTokenException("refusé"));

        assertThatThrownBy(() -> service().saveToken(userId, TOKEN))
                .isInstanceOf(InvalidGitTokenException.class);

        verify(cipher, never()).encrypt(anyString());
        verify(repository, never()).save(any());
    }

    @Test
    void doesNotPersistWhenGitHubIsUnavailable() {
        when(gitHubClient.verifyToken(TOKEN)).thenThrow(new GitHubUnavailableException("panne"));

        assertThatThrownBy(() -> service().saveToken(userId, TOKEN))
                .isInstanceOf(GitHubUnavailableException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void doesNotPersistWhenCipherIsDisabled() {
        when(gitHubClient.verifyToken(TOKEN)).thenReturn(new GitHubAccount("octocat"));
        when(cipher.encrypt(TOKEN)).thenThrow(new ByokDisabledException("chiffrement absent"));

        assertThatThrownBy(() -> service().saveToken(userId, TOKEN))
                .isInstanceOf(ByokDisabledException.class);

        // Jamais de repli en clair : rien n'est écrit si le chiffrement est indisponible.
        verify(repository, never()).save(any());
    }

    @Test
    void replacesExistingTokenInPlaceForTheSameUser() {
        UserGitCredential existing = UserGitCredential.builder()
                .id(UUID.randomUUID()).userId(userId).githubLogin("old-login")
                .encryptedDataKey("old-dk").cipherIv("old-iv").ciphertext("old-ct")
                .tokenLast4("0000").build();
        when(gitHubClient.verifyToken(TOKEN)).thenReturn(new GitHubAccount("octocat"));
        when(cipher.encrypt(TOKEN)).thenReturn(encrypted());
        when(repository.findByUserId(userId)).thenReturn(Optional.of(existing));
        when(repository.save(any(UserGitCredential.class))).thenAnswer(call -> call.getArgument(0));

        service().saveToken(userId, TOKEN);

        ArgumentCaptor<UserGitCredential> captor = ArgumentCaptor.forClass(UserGitCredential.class);
        verify(repository).save(captor.capture());
        // Même ligne réutilisée : au plus un jeton par utilisateur.
        assertThat(captor.getValue().getId()).isEqualTo(existing.getId());
        assertThat(captor.getValue().getTokenLast4()).isEqualTo("AB12");
        assertThat(captor.getValue().getGithubLogin()).isEqualTo("octocat");
    }

    @Test
    void statusIsAbsentWhenNoTokenIsStored() {
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());

        GitTokenStatusResponse status = service().getStatus(userId);

        assertThat(status.present()).isFalse();
        assertThat(status.maskedToken()).isNull();
        assertThat(status.githubLogin()).isNull();
    }

    @Test
    void deleteIsIdempotentAndScopedToTheUser() {
        service().deleteToken(userId);

        verify(repository).deleteByUserId(userId);
    }

    @Test
    void resolveTokenDecryptsForInternalUseOnly() {
        UserGitCredential stored = UserGitCredential.builder()
                .userId(userId).encryptedDataKey("dk").cipherIv("iv").ciphertext("ct")
                .tokenLast4("AB12").build();
        when(repository.findByUserId(userId)).thenReturn(Optional.of(stored));
        when(cipher.decrypt(new EncryptedKey("dk", "iv", "ct"))).thenReturn(TOKEN);

        assertThat(service().resolveToken(userId)).contains(TOKEN);
    }

    @Test
    void resolveTokenIsEmptyWhenUserHasNoToken() {
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());

        assertThat(service().resolveToken(userId)).isEmpty();
        verifyNoInteractions(cipher);
    }
}
