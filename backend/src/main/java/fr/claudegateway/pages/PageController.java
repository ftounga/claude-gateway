package fr.claudegateway.pages;

import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.pages.dto.PageResponse;
import jakarta.servlet.http.HttpServletRequest;

/**
 * <b>Les pages d'un compte</b>, lues par leur propriétaire (F-109 / SF-109-01). Authentifié par JWT ;
 * l'utilisateur vient toujours du contexte de sécurité.
 */
@RestController
@RequestMapping("/pages")
public class PageController {

    private final PageService pageService;
    private final PageViewTicketService tickets;
    private final CurrentUser currentUser;

    public PageController(PageService pageService, PageViewTicketService tickets, CurrentUser currentUser) {
        this.pageService = pageService;
        this.tickets = tickets;
        this.currentUser = currentUser;
    }

    /** Une page du compte, avec l'adresse de lecture de sa version courante. */
    @GetMapping("/{id}")
    public PageResponse get(@PathVariable UUID id, HttpServletRequest request) {
        UUID userId = currentUser.requireId();
        Page page = pageService.require(userId, id);
        return PageResponse.of(page, viewUrl(request, tickets.issue(userId, page.getId(), 0)));
    }

    /**
     * Le HTML d'une page du compte, <b>sous la politique de §3</b>.
     *
     * @param version  version voulue (courante par défaut)
     * @param download {@code true} pour télécharger le fichier
     */
    @GetMapping("/{id}/content")
    public ResponseEntity<byte[]> content(@PathVariable UUID id,
            @RequestParam(required = false) Integer version,
            @RequestParam(defaultValue = "false") boolean download) {
        UUID userId = currentUser.requireId();
        PageService.PageContent content = pageService.html(userId, id, version);
        HttpHeaders headers = PageContentPolicy.headers(content.contentType());
        if (download) {
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(fileName(content.page(), content.version()), java.nio.charset.StandardCharsets.UTF_8)
                    .build());
        }
        return new ResponseEntity<>(content.content(), headers, HttpStatus.OK);
    }

    /** L'adresse de lecture d'un ticket, sous le chemin de contexte ({@code /api}). */
    static String viewUrl(HttpServletRequest request, String ticket) {
        return request.getContextPath() + "/p/" + ticket + "/";
    }

    /** {@code titre-de-la-page-v3.html} : lisible, sans caractère qu'un système de fichiers refuserait. */
    static String fileName(Page page, int version) {
        String slug = java.text.Normalizer.normalize(page.getTitle(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        if (slug.isEmpty()) {
            slug = "page";
        }
        if (slug.length() > 60) {
            slug = slug.substring(0, 60);
        }
        return slug + "-v" + version + ".html";
    }
}
