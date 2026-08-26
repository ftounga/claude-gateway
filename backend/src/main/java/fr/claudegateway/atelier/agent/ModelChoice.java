package fr.claudegateway.atelier.agent;

/**
 * Modèle et effort de raisonnement retenus pour une session (F-28 / SF-28-17).
 *
 * <p>Envoyés en <b>surcharge de session</b> plutôt que portés par l'agent provisionné : l'agent est un
 * objet versionné, partagé par toutes les sessions, et le mettre à jour à chaque changement de
 * configuration demanderait une réconciliation. La surcharge obtient le même résultat immédiatement,
 * et se défait en une variable d'environnement.</p>
 *
 * @param id     identifiant du modèle servi
 * @param effort niveau d'effort ({@code low} à {@code max}), déjà validé par la configuration
 */
public record ModelChoice(String id, String effort) {
}
