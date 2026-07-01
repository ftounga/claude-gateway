package fr.claudegateway.byok;

/**
 * Chiffre et déchiffre une clé API utilisateur (BYOK, F-03) via <b>envelope encryption</b>
 * (OQ-06 tranchée : AWS KMS).
 *
 * <p>La clé API en clair ne transite qu'en argument d'{@link #encrypt(String)} et en valeur de
 * retour de {@link #decrypt(EncryptedKey)} : elle n'est jamais stockée ni journalisée. Le blob
 * {@link EncryptedKey} ne contient que des données chiffrées (base64).</p>
 *
 * <p>Si aucun mécanisme de chiffrement n'est configuré, l'implémentation est <b>dormante</b> et
 * lève {@link ByokDisabledException} (traduite en 503) à tout usage — sans empêcher le démarrage
 * de l'application.</p>
 */
public interface ByokKeyCipher {

    /**
     * Chiffre une clé API en clair.
     *
     * @param plaintextApiKey clé API utilisateur en clair (jamais journalisée)
     * @return le blob chiffré à persister
     * @throws ByokDisabledException si le chiffrement BYOK n'est pas configuré
     */
    EncryptedKey encrypt(String plaintextApiKey);

    /**
     * Déchiffre un blob précédemment produit par cette implémentation.
     *
     * @param encryptedKey blob chiffré issu de la persistance
     * @return la clé API en clair (à utiliser puis oublier, jamais persistée)
     * @throws ByokDisabledException si le chiffrement BYOK n'est pas configuré
     */
    String decrypt(EncryptedKey encryptedKey);
}
