package fr.claudegateway.atelier.git.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Corps de {@code POST /api/workspaces/{id}/git/commit} (F-31 / SF-31-08) : publier les modifications
 * faites par l'utilisateur en un commit sur une branche dédiée.
 *
 * <p>Le corps ne porte <b>plus les fichiers</b> depuis SF-31-12 : la publication prend tout le
 * travail non publié du projet — éditions de l'écran et sorties de l'agent confondues — au lieu de
 * la seule sélection que l'écran aurait envoyée. C'est ce qui unifie les deux chemins d'écriture.</p>
 *
 * @param branch  branche cible ; refusée si c'est la branche par défaut du dépôt
 * @param message message de commit
 */
public record GitCommitRequest(
        @NotBlank(message = "Le nom de la branche est requis.")
        @Size(max = 255, message = "Nom de branche trop long.")
        String branch,

        @NotBlank(message = "Le message de commit est requis.")
        @Size(max = 500, message = "Message de commit trop long.")
        String message) {

}
