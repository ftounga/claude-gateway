package fr.claudegateway.byok;

import java.security.GeneralSecurityException;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Primitive de chiffrement symétrique authentifié AES-GCM, partagée par les implémentations
 * {@link ByokKeyCipher}. Chaque chiffrement utilise un IV aléatoire de 12 octets et un tag
 * d'authentification de 128 bits (inclus dans le ciphertext).
 *
 * <p>Utilitaire interne sans état métier : ne journalise rien et ne conserve aucune donnée.</p>
 */
final class AesGcm {

    static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final SecureRandom RANDOM = new SecureRandom();

    private AesGcm() {
    }

    /** Résultat d'un chiffrement : l'IV aléatoire utilisé et le ciphertext (tag inclus). */
    record Sealed(byte[] iv, byte[] ciphertext) {
    }

    /** Chiffre {@code plaintext} avec {@code key} (16/24/32 octets) sous un IV aléatoire. */
    static Sealed encrypt(byte[] key, byte[] plaintext) {
        byte[] iv = new byte[IV_LENGTH];
        RANDOM.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return new Sealed(iv, ciphertext);
        } catch (GeneralSecurityException ex) {
            // Aucun secret n'est inclus dans le message : ni la clé, ni le plaintext.
            throw new IllegalStateException("Échec du chiffrement AES-GCM", ex);
        }
    }

    /** Déchiffre {@code ciphertext} avec {@code key} et {@code iv}. Lève si le tag est invalide. */
    static byte[] decrypt(byte[] key, byte[] iv, byte[] ciphertext) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Échec du déchiffrement AES-GCM", ex);
        }
    }
}
