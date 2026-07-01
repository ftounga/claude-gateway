package fr.claudegateway.byok;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.AIProviderException;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ModelCatalog;
import fr.claudegateway.byok.dto.ApiKeyStatusResponse;

/**
 * Tests unitaires de {@link ByokKeyService} : validation de format, validation par appel test réel,
 * chiffrement/persistance, statut masqué et suppression.
 */
@ExtendWith(MockitoExtension.class)
class ByokKeyServiceTest {

    @Mock
    private UserApiKeyRepository repository;
    @Mock
    private ByokKeyCipher cipher;
    @Mock
    private AIProvider aiProvider;
    @Mock
    private ModelCatalog modelCatalog;

    private final UUID userId = UUID.randomUUID();

    private ByokKeyService service() {
        return new ByokKeyService(repository, cipher, aiProvider, modelCatalog);
    }

    @Test
    void saveRejectsInvalidFormatWithoutCallingProviderOrPersisting() {
        assertThatThrownBy(() -> service().saveKey(userId, "not-a-key"))
                .isInstanceOf(InvalidApiKeyException.class);

        verify(aiProvider, never()).complete(any());
        verify(repository, never()).save(any());
    }

    @Test
    void saveRejectsKeyWhenProviderRefusesAndPersistsNothing() {
        when(modelCatalog.defaultModel()).thenReturn("claude-opus-4-8");
        when(aiProvider.complete(any(ChatCompletionRequest.class)))
                .thenThrow(new AIProviderException("upstream 401"));

        assertThatThrownBy(() -> service().saveKey(userId, "sk-ant-invalid-1234"))
                .isInstanceOf(InvalidApiKeyException.class);

        verify(cipher, never()).encrypt(any());
        verify(repository, never()).save(any());
    }

    @Test
    void saveEncryptsAndPersistsAndReturnsMaskedStatus() {
        String apiKey = "sk-ant-api03-secret-XY99";
        when(modelCatalog.defaultModel()).thenReturn("claude-opus-4-8");
        when(aiProvider.complete(any(ChatCompletionRequest.class)))
                .thenReturn(new ChatCompletionResult("ok", "claude-opus-4-8", 1, 1));
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());
        when(cipher.encrypt(apiKey)).thenReturn(new EncryptedKey("edk", "iv", "ct"));
        when(repository.save(any(UserApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        ApiKeyStatusResponse status = service().saveKey(userId, "  " + apiKey + "  ");

        assertThat(status.present()).isTrue();
        assertThat(status.last4()).isEqualTo("XY99");
        assertThat(status.maskedKey()).isEqualTo("sk-…XY99");
        assertThat(status.mode()).isEqualTo("BYOK");
        assertThat(status.provider()).isEqualTo("ANTHROPIC");
        // Le clair n'apparaît jamais dans la réponse.
        assertThat(status.maskedKey()).doesNotContain(apiKey);

        org.mockito.ArgumentCaptor<UserApiKey> captor = org.mockito.ArgumentCaptor.forClass(UserApiKey.class);
        verify(repository).save(captor.capture());
        UserApiKey saved = captor.getValue();
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getCiphertext()).isEqualTo("ct");
        assertThat(saved.getKeyLast4()).isEqualTo("XY99");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getValidatedAt()).isNotNull();
    }

    @Test
    void getStatusReturnsHostedWhenNoKey() {
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());

        ApiKeyStatusResponse status = service().getStatus(userId);

        assertThat(status.present()).isFalse();
        assertThat(status.mode()).isEqualTo("HOSTED");
        assertThat(status.maskedKey()).isNull();
    }

    @Test
    void deleteKeyIsIdempotentAndFiltersByUser() {
        service().deleteKey(userId);
        verify(repository).deleteByUserId(userId);
    }

    @Test
    void saveValidationCallCarriesTheOverrideKey() {
        when(modelCatalog.defaultModel()).thenReturn("claude-opus-4-8");
        when(aiProvider.complete(any(ChatCompletionRequest.class)))
                .thenReturn(new ChatCompletionResult("ok", "claude-opus-4-8", 1, 1));
        when(repository.findByUserId(userId)).thenReturn(Optional.empty());
        when(cipher.encrypt(any())).thenReturn(new EncryptedKey("edk", "iv", "ct"));
        when(repository.save(any(UserApiKey.class))).thenAnswer(inv -> inv.getArgument(0));

        service().saveKey(userId, "sk-ant-key-ABCD");

        org.mockito.ArgumentCaptor<ChatCompletionRequest> captor =
                org.mockito.ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(aiProvider).complete(captor.capture());
        // La clé de l'utilisateur est bien passée en override provider-neutre (jamais la plateforme).
        assertThat(captor.getValue().apiKey()).isEqualTo("sk-ant-key-ABCD");
        assertThat(captor.getValue().messages()).hasSize(1);
    }
}
