package fr.claudegateway.atelier.agent;

/**
 * Montage d'un fichier du workspace dans une session Managed Agents (F-28 / Phase 2, ADR-013).
 * Associe un fichier déjà téléversé chez le fournisseur ({@code fileId}) à son chemin de montage
 * dans le bac à sable ({@code mountPath}, p. ex. {@code /workspace/src/App.java}).
 *
 * @param fileId    identifiant fournisseur du fichier téléversé
 * @param mountPath chemin de montage dans la session (sous {@code /workspace/})
 */
public record FileMount(String fileId, String mountPath) {
}
