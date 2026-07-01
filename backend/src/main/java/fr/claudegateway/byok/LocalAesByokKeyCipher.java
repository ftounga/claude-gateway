package fr.claudegateway.byok;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Implémentation {@link ByokKeyCipher} <b>locale</b>, réservée au dev et aux tests : elle reproduit
 * l'envelope encryption sans dépendre d'un vrai KMS. Une data key aléatoire chiffre la clé API en
 * AES-GCM ; la data key est elle-même enveloppée en AES-GCM par une clé maître de test fournie en
 * configuration ({@code app.byok.local-key}, base64 d'une clé AES 256 bits).
 *
 * <p>À NE JAMAIS utiliser en production (la clé maître n'est pas protégée par KMS).</p>
 */
public class LocalAesByokKeyCipher implements ByokKeyCipher {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int DATA_KEY_LENGTH = 32;

    private final byte[] masterKey;

    public LocalAesByokKeyCipher(String base64MasterKey) {
        this.masterKey = Base64.getDecoder().decode(base64MasterKey);
        if (masterKey.length != 16 && masterKey.length != 24 && masterKey.length != 32) {
            throw new IllegalStateException("app.byok.local-key doit être une clé AES de 16, 24 ou 32 octets (base64)");
        }
    }

    @Override
    public EncryptedKey encrypt(String plaintextApiKey) {
        byte[] dataKey = new byte[DATA_KEY_LENGTH];
        RANDOM.nextBytes(dataKey);
        try {
            AesGcm.Sealed payload = AesGcm.encrypt(dataKey, plaintextApiKey.getBytes(StandardCharsets.UTF_8));
            // La data key est enveloppée par la clé maître : IV du wrap concaténé au devant du blob.
            AesGcm.Sealed wrap = AesGcm.encrypt(masterKey, dataKey);
            return new EncryptedKey(
                    base64(concat(wrap.iv(), wrap.ciphertext())),
                    base64(payload.iv()),
                    base64(payload.ciphertext()));
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    @Override
    public String decrypt(EncryptedKey encryptedKey) {
        byte[] wrapped = decode(encryptedKey.encryptedDataKey());
        byte[] wrapIv = Arrays.copyOfRange(wrapped, 0, AesGcm.IV_LENGTH);
        byte[] wrappedDataKey = Arrays.copyOfRange(wrapped, AesGcm.IV_LENGTH, wrapped.length);
        byte[] dataKey = AesGcm.decrypt(masterKey, wrapIv, wrappedDataKey);
        try {
            byte[] plaintext = AesGcm.decrypt(dataKey, decode(encryptedKey.iv()), decode(encryptedKey.ciphertext()));
            return new String(plaintext, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(dataKey, (byte) 0);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] decode(String value) {
        return Base64.getDecoder().decode(value);
    }
}
