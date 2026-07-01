package fr.claudegateway.byok;

/**
 * Implémentation <b>dormante</b> retenue quand aucun mécanisme de chiffrement BYOK n'est configuré.
 * Le contexte démarre normalement ; tout usage réel (ajout de clé) lève {@link ByokDisabledException}
 * (traduite en 503), sans jamais bloquer le démarrage de l'application.
 */
public class DisabledByokKeyCipher implements ByokKeyCipher {

    private static final String MESSAGE = "Le chiffrement BYOK n'est pas configuré.";

    @Override
    public EncryptedKey encrypt(String plaintextApiKey) {
        throw new ByokDisabledException(MESSAGE);
    }

    @Override
    public String decrypt(EncryptedKey encryptedKey) {
        throw new ByokDisabledException(MESSAGE);
    }
}
