package fr.claudegateway.atelier.gallery;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Le bloc riche « Galerie » du compte rendu de tour (F-278 / SF-278-01) : ce que l'écran affiche (carrousel, vignettes,
 * nom, date, légende) et relit au rechargement. Les métadonnées vivent ICI (dans {@code terminal_json}) ; les aperçus
 * dans le stockage du terminal (route {@code GET /workspaces/{id}/gallery/{imageId}}).
 *
 * @param title   titre donné par l'agent
 * @param items   les images affichées, dans l'ordre
 * @param skipped les images écartées et leur raison (bornées)
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GalleryBlock(String title, List<Item> items, List<Skipped> skipped) {

    /**
     * Une image affichée.
     *
     * @param id      identifiant de l'aperçu
     * @param name    nom du fichier
     * @param path    chemin relatif au projet
     * @param date    date ISO (dépôt, nom du fichier) ou texte donné par l'agent ; {@code null} si inconnue
     * @param caption légende posée par l'agent, ou {@code null}
     * @param width   largeur de l'aperçu
     * @param height  hauteur de l'aperçu
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(String id, String name, String path, String date, String caption, Integer width,
            Integer height) {
    }

    /** Une image écartée : son chemin et pourquoi. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Skipped(String path, String reason) {
    }
}
