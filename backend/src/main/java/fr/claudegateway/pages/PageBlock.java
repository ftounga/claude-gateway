package fr.claudegateway.pages;

import java.util.UUID;

/**
 * <b>Le bloc « Page publiée »</b> tel que le terminal le montre (F-109 / SF-109-03) : de quoi l'afficher et
 * l'ouvrir, <b>jamais le contenu</b> — la page se relit par {@code GET /pages/{id}}, isolée par compte.
 *
 * @param pageId      identifiant de la page
 * @param title       titre
 * @param description phrase de description, ou {@code null}
 * @param version     version publiée par cet appel
 */
public record PageBlock(UUID pageId, String title, String description, int version, boolean pdf) {

    /** Le bloc d'une publication. */
    public static PageBlock of(PageService.PublishedPage published) {
        return new PageBlock(published.page().getId(), published.page().getTitle(),
                published.page().getDescription(), published.version().getVersion(), false);
    }

    /**
     * Le bloc « PDF prêt » (F-184 / SF-184-04) : la même page, marquée {@code pdf} — le terminal y pose le
     * bouton « Télécharger le PDF ». Additif : un bloc de publication garde {@code pdf = false}.
     */
    public static PageBlock pdfOf(UUID pageId, String title, int version) {
        return new PageBlock(pageId, title, null, version, true);
    }
}
