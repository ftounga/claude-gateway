package fr.claudegateway.atelier.agent;

/**
 * Fichier de sortie produit par une session Managed Agents (F-28 / Phase 2, ADR-013), listé via
 * l'API Files avec le {@code scope_id} de la session.
 *
 * @param fileId   identifiant fournisseur du fichier de sortie
 * @param filename nom (ou chemin) du fichier tel que rapporté par le fournisseur
 */
public record OutputFile(String fileId, String filename) {
}
