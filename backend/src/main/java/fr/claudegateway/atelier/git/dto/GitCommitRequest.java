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
 * <p>Tout est obligatoire, contrairement au push de SF-31-04 : ici c'est l'utilisateur qui choisit
 * ce qu'il publie et sous quel message, on ne devine rien à sa place.</p>
 *
 * @param branch  branche cible ; refusée si c'est la branche du projet
 * @param message message de commit
 * @param files   fichiers à publier, contenu complet (pas de patch)
 */
public record GitCommitRequest(
        @NotBlank(message = "Le nom de la branche est requis.")
        @Size(max = 255, message = "Nom de branche trop long.")
        String branch,

        @NotBlank(message = "Le message de commit est requis.")
        @Size(max = 500, message = "Message de commit trop long.")
        String message,

        @NotEmpty(message = "Aucun fichier à publier.")
        @Size(max = 50, message = "Trop de fichiers dans un seul commit (50 au maximum).")
        List<@Valid FileEdit> files) {

    /**
     * Un fichier du commit.
     *
     * <p>Le motif du chemin ferme la sortie de dépôt côté requête : ni chemin absolu, ni segment
     * {@code ..}, ni antislash. Rien de fautif ne doit atteindre l'appel à GitHub.</p>
     *
     * @param path    chemin relatif dans le dépôt
     * @param content contenu texte complet du fichier
     */
    public record FileEdit(
            @NotBlank(message = "Le chemin du fichier est requis.")
            @Size(max = 400, message = "Chemin de fichier trop long.")
            @Pattern(regexp = "^(?!/)(?!.*\\.\\.)(?!.*\\\\)[^\\u0000]+$",
                    message = "Chemin de fichier invalide.")
            String path,

            // Un contenu vide est légitime (fichier vidé) ; un contenu absent ne l'est pas.
            @NotNull(message = "Le contenu du fichier est requis.")
            @Size(max = 524288, message = "Fichier trop volumineux pour être publié (512 Kio au maximum).")
            String content) {
    }
}
