package fr.claudegateway.jarvis.portrait;

import org.springframework.stereotype.Component;

import fr.claudegateway.byok.ByokKeyCipher;
import fr.claudegateway.byok.EncryptedKey;

/**
 * <b>Le scellé du portrait</b> (F-241 / SF-241-01, D19) — réutilise l'enveloppe {@link ByokKeyCipher} (KMS en prod,
 * AES local en tests, dormant sinon : {@code ByokDisabledException} ⇒ 503, jamais de clair stocké). Une colonne porte
 * {@code dataKey.iv.ciphertext} en base64 (le point n'est pas un caractère base64). Le clair n'est jamais journalisé.
 */
@Component
public class PortraitSeal {

    private final ByokKeyCipher cipher;

    public PortraitSeal(ByokKeyCipher cipher) {
        this.cipher = cipher;
    }

    /** Scelle un texte (vide compris). */
    public String seal(String plain) {
        EncryptedKey e = cipher.encrypt(plain == null ? "" : plain);
        return e.encryptedDataKey() + "." + e.iv() + "." + e.ciphertext();
    }

    /** Ouvre un scellé ; {@code null} ⇒ {@code null}. Un scellé illisible lève {@link IllegalStateException}. */
    public String open(String sealed) {
        if (sealed == null) {
            return null;
        }
        String[] parts = sealed.split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalStateException("scellé illisible");
        }
        return cipher.decrypt(new EncryptedKey(parts[0], parts[1], parts[2]));
    }
}
