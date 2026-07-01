package fr.claudegateway.byok;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires de l'implémentation dormante : tout usage lève {@link ByokDisabledException}.
 */
class DisabledByokKeyCipherTest {

    private final DisabledByokKeyCipher cipher = new DisabledByokKeyCipher();

    @Test
    void encryptThrowsByokDisabled() {
        assertThatThrownBy(() -> cipher.encrypt("sk-ant-key"))
                .isInstanceOf(ByokDisabledException.class);
    }

    @Test
    void decryptThrowsByokDisabled() {
        assertThatThrownBy(() -> cipher.decrypt(new EncryptedKey("a", "b", "c")))
                .isInstanceOf(ByokDisabledException.class);
    }
}
