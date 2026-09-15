package fr.claudegateway.vigie.dto;

/**
 * Une ligne de la check-list de mise en service (F-122 / SF-122-02).
 *
 * @param check  identifiant de la vérification ({@code VigieReadinessCheck#name()})
 * @param status statut ({@code VigieCheckStatus#name()} : {@code OK}, {@code KO} ou {@code PENDING})
 * @param detail message court destiné à l'affichage
 */
public record VigieReadinessItem(String check, String status, String detail) {
}
