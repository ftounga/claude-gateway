package fr.claudegateway.atelier.dto;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

/**
 * Corps de {@code POST /workspaces/{id}/import-library} : identifiants des documents de la
 * bibliothèque personnelle (F-08) à importer dans le workspace de l'Atelier. Chaque document est
 * relu sous double filtre {@code id} + {@code user_id} (isolation) puis son texte extrait est écrit
 * dans le workspace.
 *
 * @param documentIds identifiants des documents à importer (1 à 10, non vide)
 */
public record AtelierImportLibraryRequest(
        @NotEmpty @Size(max = 10) List<UUID> documentIds) {
}
