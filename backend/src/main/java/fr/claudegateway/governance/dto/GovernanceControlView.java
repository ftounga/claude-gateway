package fr.claudegateway.governance.dto;

/**
 * Un contrôle du serveur, présenté (F-51 / SF-51-01).
 *
 * @param id          identifiant stable cité par les paquets
 * @param kind        point d'accroche de F-50 : {@code AFTER_FILE_WRITE} ou {@code END_OF_TURN}
 * @param description ce que le contrôle vérifie, en une ligne
 * @param known       vrai si le serveur connaît ce contrôle ; faux pour un identifiant cité par un
 *                    paquet et absent du produit (un contrôle retiré depuis la publication)
 */
public record GovernanceControlView(String id, String kind, String description, boolean known) {
}
