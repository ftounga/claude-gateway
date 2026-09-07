package fr.claudegateway.billing.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Requête de création d'une session de paiement (F-09, étendue par F-43). Le {@code planCode} est
 * validé contre le catalogue côté service (400 si inconnu).
 *
 * @param planCode code du plan à souscrire (ex. {@code PRO})
 * @param period   périodicité d'engagement souhaitée : {@code MONTHLY} ou {@code YEARLY} (F-43).
 *                 <b>Optionnelle</b> — absente, elle vaut {@code MONTHLY}, pour que le contrat
 *                 d'origine continue de fonctionner sans modification. {@code DAILY} est refusé :
 *                 c'est la nature du pass journée, pas un choix d'achat.
 */
public record CheckoutRequest(
        @NotBlank String planCode,
        @Size(max = 16) String period) {
}
