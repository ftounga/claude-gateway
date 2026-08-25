package fr.claudegateway.git;

import java.util.UUID;

/**
 * Le jeton GitHub d'un utilisateur a été <b>remplacé ou retiré</b>, et la copie déposée chez le
 * fournisseur d'agents doit disparaître avec lui (F-31 / SF-31-05).
 *
 * <p><b>Pourquoi un événement</b> : le domaine {@code git} détient le jeton, mais ignore tout du
 * fournisseur d'agents — c'est {@code atelier.agent} qui parle au fournisseur. Un appel direct
 * inverserait cette dépendance (et créerait un cycle de paquets). L'événement laisse chacun à sa
 * place : {@code git} annonce une révocation, l'Atelier en tire la conséquence.</p>
 *
 * <p>Il est consommé <b>après commit</b> : détruire un vault pour une transaction qui n'a pas été
 * validée laisserait l'utilisateur avec un jeton en base et plus de vault chez le fournisseur.</p>
 *
 * <p>Aucun secret n'est porté : seuls l'utilisateur et des identifiants opaques.</p>
 *
 * @param userId       propriétaire du jeton révoqué
 * @param vaultId      vault à détruire chez le fournisseur (jamais {@code null} : l'événement n'est
 *                     publié que lorsqu'un vault existait)
 * @param credentialId credential déposée dans ce vault, ou {@code null} si elle n'a pas été mémorisée
 */
public record GitVaultRevokedEvent(UUID userId, String vaultId, String credentialId) {
}
