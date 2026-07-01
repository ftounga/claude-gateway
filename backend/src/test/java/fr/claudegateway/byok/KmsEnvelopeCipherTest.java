package fr.claudegateway.byok;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

/**
 * Tests unitaires de l'envelope encryption KMS avec un {@link KmsClient} mocké : la logique
 * cryptographique locale (AES-GCM) est validée sans dépendre d'un vrai KMS.
 */
@ExtendWith(MockitoExtension.class)
class KmsEnvelopeCipherTest {

    private static final String KMS_KEY_ID = "alias/claude-gateway-staging-byok";

    @Mock
    private KmsClient kmsClient;

    @Test
    void roundTripUsesGenerateDataKeyThenDecrypt() {
        // Data key en clair fixe simulée par KMS, et un CiphertextBlob opaque.
        byte[] plaintextDataKey = new byte[32];
        new SecureRandom().nextBytes(plaintextDataKey);
        byte[] encryptedDataKeyBlob = "opaque-kms-encrypted-data-key".getBytes(StandardCharsets.UTF_8);

        when(kmsClient.generateDataKey(any(GenerateDataKeyRequest.class)))
                .thenReturn(GenerateDataKeyResponse.builder()
                        .plaintext(SdkBytes.fromByteArray(plaintextDataKey))
                        .ciphertextBlob(SdkBytes.fromByteArray(encryptedDataKeyBlob))
                        .build());
        // Au déchiffrement, KMS restitue la même data key en clair.
        when(kmsClient.decrypt(any(DecryptRequest.class)))
                .thenReturn(DecryptResponse.builder()
                        .plaintext(SdkBytes.fromByteArray(plaintextDataKey))
                        .build());

        KmsEnvelopeCipher cipher = new KmsEnvelopeCipher(kmsClient, KMS_KEY_ID);
        String apiKey = "sk-ant-api03-secret-1234567890";

        EncryptedKey encrypted = cipher.encrypt(apiKey);
        String decrypted = cipher.decrypt(encrypted);

        assertThat(decrypted).isEqualTo(apiKey);
        // Le blob persisté n'est jamais le clair.
        assertThat(encrypted.ciphertext()).doesNotContain(apiKey);
    }

    @Test
    void generateDataKeyTargetsConfiguredKmsKey() {
        byte[] dataKey = new byte[32];
        when(kmsClient.generateDataKey(any(GenerateDataKeyRequest.class)))
                .thenReturn(GenerateDataKeyResponse.builder()
                        .plaintext(SdkBytes.fromByteArray(dataKey))
                        .ciphertextBlob(SdkBytes.fromByteArray("blob".getBytes(StandardCharsets.UTF_8)))
                        .build());

        new KmsEnvelopeCipher(kmsClient, KMS_KEY_ID).encrypt("sk-ant-key");

        ArgumentCaptor<GenerateDataKeyRequest> captor = ArgumentCaptor.forClass(GenerateDataKeyRequest.class);
        org.mockito.Mockito.verify(kmsClient).generateDataKey(captor.capture());
        assertThat(captor.getValue().keyId()).isEqualTo(KMS_KEY_ID);
        assertThat(captor.getValue().keySpecAsString()).isEqualTo("AES_256");
    }
}
