package fr.claudegateway.mail;

/**
 * Le destinataire résolu par la gateway pour un poste (F-110 / SF-110-01).
 *
 * @param address           l'adresse où envoyer
 * @param verifiedForClient vrai si c'est l'adresse vérifiée du client ; faux si c'est le repli sur l'adresse
 *                          du compte — l'appelant doit alors le dire
 * @param clientName        nom du client (poste), pour le dire en clair
 */
public record ResolvedRecipient(String address, boolean verifiedForClient, String clientName) {
}
