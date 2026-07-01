package fr.claudegateway.byok;

/**
 * Blob chiffré d'une clé API utilisateur (envelope encryption). Conteneur neutre : il ne porte
 * <b>jamais</b> la clé en clair, uniquement des données chiffrées encodées en base64.
 *
 * <p>Le format interne des trois champs dépend de l'implémentation {@link ByokKeyCipher} qui l'a
 * produit ; c'est cette même implémentation qui sait le relire. Pour KMS, {@code encryptedDataKey}
 * est le {@code CiphertextBlob} de la data key ; {@code iv}/{@code ciphertext} sont le chiffrement
 * AES-GCM local de la clé API.</p>
 *
 * @param encryptedDataKey data key chiffrée (base64)
 * @param iv               vecteur d'initialisation AES-GCM du chiffrement de la clé API (base64)
 * @param ciphertext       clé API chiffrée en AES-GCM, tag inclus (base64)
 */
public record EncryptedKey(String encryptedDataKey, String iv, String ciphertext) {
}
