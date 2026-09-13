package fr.claudegateway.mail;

import java.time.OffsetDateTime;

/**
 * L'état de l'adresse de réception d'un poste, tel que l'écran le lit (F-110 / SF-110-01). Ne porte jamais
 * le code ni son empreinte.
 *
 * @param address       adresse déclarée (vérifiée ou non), ou {@code null}
 * @param verified      vrai si l'adresse déclarée est vérifiée
 * @param verifiedAt    instant de la vérification
 * @param codePending   vrai si un code valable attend d'être saisi
 * @param codeExpiresAt fin de validité du code en attente
 * @param accountEmail  adresse du compte (repli)
 * @param recipient     destinataire effectif des courriels du poste
 * @param fallback      vrai quand le destinataire est l'adresse du compte
 * @param clientName    nom du client
 */
public record HostMailAddressView(String address, boolean verified, OffsetDateTime verifiedAt,
        boolean codePending, OffsetDateTime codeExpiresAt, String accountEmail, String recipient,
        boolean fallback, String clientName) {
}
