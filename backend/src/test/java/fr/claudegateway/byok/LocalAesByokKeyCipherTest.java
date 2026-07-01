package fr.claudegateway.byok;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires du chiffrement local AES-GCM (envelope) utilisé en dev/tests.
 */
class LocalAesByokKeyCipherTest {

    // Clé maître AES-256 de test (32 octets, base64).
    private static final String MASTER_KEY = "Ynlvay1sb2NhbC10ZXN0LW1hc3Rlci1rZXktMzJieXQ=";

    private final LocalAesByokKeyCipher cipher = new LocalAesByokKeyCipher(MASTER_KEY);

    @Test
    void roundTripRestoresOriginalKey() {
        String apiKey = "sk-ant-api03-secret-value-1234567890";

        EncryptedKey encrypted = cipher.encrypt(apiKey);

        assertThat(cipher.decrypt(encrypted)).isEqualTo(apiKey);
    }

    @Test
    void neverStoresPlaintextInBlob() {
        String apiKey = "sk-ant-super-secret";

        EncryptedKey encrypted = cipher.encrypt(apiKey);

        // Le blob (base64) ne doit jamais contenir la clé en clair, ni son encodage base64.
        String base64ApiKey = Base64.getEncoder().encodeToString(apiKey.getBytes());
        assertThat(encrypted.ciphertext()).doesNotContain(apiKey).doesNotContain(base64ApiKey);
        assertThat(encrypted.encryptedDataKey()).doesNotContain(apiKey);
        assertThat(encrypted.toString()).doesNotContain(apiKey);
    }

    @Test
    void twoEncryptionsOfSameKeyDifferByRandomIv() {
        String apiKey = "sk-ant-same-key";

        EncryptedKey first = cipher.encrypt(apiKey);
        EncryptedKey second = cipher.encrypt(apiKey);

        // IV aléatoire + data key aléatoire => ciphertext distincts, tous deux déchiffrables.
        assertThat(first.iv()).isNotEqualTo(second.iv());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(cipher.decrypt(first)).isEqualTo(apiKey);
        assertThat(cipher.decrypt(second)).isEqualTo(apiKey);
    }

    @Test
    void rejectsMasterKeyOfInvalidLength() {
        String tooShort = Base64.getEncoder().encodeToString(new byte[10]);

        assertThatThrownBy(() -> new LocalAesByokKeyCipher(tooShort))
                .isInstanceOf(IllegalStateException.class);
    }
}
