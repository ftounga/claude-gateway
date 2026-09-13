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
public record PageBlock(UUID pageId, String title, String description, int version) {

    /** Le bloc d'une publication. */
    public static PageBlock of(PageService.PublishedPage published) {
        return new PageBlock(published.page().getId(), published.page().getTitle(),
                published.page().getDescription(), published.version().getVersion());
    }
}
