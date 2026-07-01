package fr.claudegateway.byok;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

/**
 * Implémentation {@link ByokKeyCipher} par <b>envelope encryption</b> AWS KMS (OQ-06).
 *
 * <p>Chiffrement : KMS {@code GenerateDataKey} (AES_256) produit une data key en clair (utilisée
 * localement pour un chiffrement AES-GCM de la clé API) et sa version chiffrée (persistée). La data
 * key en clair est effacée de la mémoire après usage. Déchiffrement : KMS {@code Decrypt} restitue
 * la data key, qui déchiffre le ciphertext AES-GCM.</p>
 *
 * <p>La clé API en clair n'est jamais envoyée à KMS (seule la petite data key l'est) et n'est
 * jamais journalisée.</p>
 */
public class KmsEnvelopeCipher implements ByokKeyCipher {

    private final KmsClient kmsClient;
    private final String kmsKeyId;

    public KmsEnvelopeCipher(KmsClient kmsClient, String kmsKeyId) {
        this.kmsClient = kmsClient;
        this.kmsKeyId = kmsKeyId;
    }

    @Override
    public EncryptedKey encrypt(String plaintextApiKey) {
        GenerateDataKeyResponse dataKey = kmsClient.generateDataKey(GenerateDataKeyRequest.builder()
                .keyId(kmsKeyId)
                .keySpec(DataKeySpec.AES_256)
                .build());

        byte[] plaintextDataKey = dataKey.plaintext().asByteArray();
        try {
            AesGcm.Sealed sealed = AesGcm.encrypt(plaintextDataKey,
                    plaintextApiKey.getBytes(StandardCharsets.UTF_8));
            return new EncryptedKey(
                    base64(dataKey.ciphertextBlob().asByteArray()),
                    base64(sealed.iv()),
                    base64(sealed.ciphertext()));
        } finally {
            // La data key en clair ne doit pas subsister en mémoire au-delà de l'usage.
            Arrays.fill(plaintextDataKey, (byte) 0);
        }
    }

    @Override
    public String decrypt(EncryptedKey encryptedKey) {
        byte[] plaintextDataKey = kmsClient.decrypt(DecryptRequest.builder()
                        .keyId(kmsKeyId)
                        .ciphertextBlob(SdkBytes.fromByteArray(decode(encryptedKey.encryptedDataKey())))
                        .build())
                .plaintext().asByteArray();
        try {
            byte[] plaintext = AesGcm.decrypt(plaintextDataKey,
                    decode(encryptedKey.iv()), decode(encryptedKey.ciphertext()));
            return new String(plaintext, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plaintextDataKey, (byte) 0);
        }
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] decode(String value) {
        return Base64.getDecoder().decode(value);
    }
}
