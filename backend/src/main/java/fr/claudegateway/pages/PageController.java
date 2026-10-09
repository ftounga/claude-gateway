package fr.claudegateway.pages;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.pages.dto.PageResponse;
import fr.claudegateway.pages.dto.PageVersionResponse;
import fr.claudegateway.pages.dto.RenamePageRequest;
import fr.claudegateway.pages.pdf.PagePdfService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * <b>Les pages d'un compte</b>, lues et rangées par leur propriétaire (F-109 / SF-109-01, SF-109-04). Authentifié
 * par JWT ; l'utilisateur vient toujours du contexte de sécurité.
 *
 * <p>Aucun droit d'espace n'est exigé ici : le droit ouvre la capacité de <b>produire</b> une page
 * (SF-109-02) ; un compte qui a résilié garde l'accès à ce qu'il a produit.</p>
 */
@RestController
@RequestMapping("/pages")
public class PageController {

    private final PageService pageService;
    private final PageViewTicketService tickets;
    private final CurrentUser currentUser;
    private final PagePdfService pagePdfService;

    public PageController(PageService pageService, PageViewTicketService tickets, CurrentUser currentUser,
            PagePdfService pagePdfService) {
        this.pageService = pageService;
        this.tickets = tickets;
        this.currentUser = currentUser;
        this.pagePdfService = pagePdfService;
    }

    /** Les pages du compte à un lieu, chacune avec l'adresse de lecture de sa version courante. */
    @GetMapping
    public List<PageResponse> list(@RequestParam UUID hostId, @RequestParam String space, HttpServletRequest request) {
        UUID userId = currentUser.requireId();
        return pageService.list(userId, hostId, parseSpace(space)).stream()
                .map(page -> PageResponse.of(page, viewUrl(request, tickets.issue(userId, page.getId(), 0))))
                .toList();
    }

    /**
     * Une page du compte, avec l'adresse de lecture de sa version courante — ou de la version demandée.
     *
     * @param version version voulue, conservée (courante par défaut)
     */
    @GetMapping("/{id}")
    public PageResponse get(@PathVariable UUID id, @RequestParam(required = false) Integer version,
            HttpServletRequest request) {
        UUID userId = currentUser.requireId();
        Page page = pageService.require(userId, id);
        int ticketVersion = version == null ? 0 : pageService.requireVersion(userId, id, version);
        return PageResponse.of(page, viewUrl(request, tickets.issue(userId, page.getId(), ticketVersion)));
    }

    /** Les versions conservées d'une page du compte, la plus récente d'abord. */
    @GetMapping("/{id}/versions")
    public List<PageVersionResponse> versions(@PathVariable UUID id) {
        return pageService.versions(currentUser.requireId(), id).stream().map(PageVersionResponse::of).toList();
    }

    /** Renomme une page du compte. */
    @PatchMapping("/{id}")
    public PageResponse rename(@PathVariable UUID id, @RequestBody RenamePageRequest body, HttpServletRequest request) {
        UUID userId = currentUser.requireId();
        Page page = pageService.rename(userId, id, body == null ? null : body.title());
        return PageResponse.of(page, viewUrl(request, tickets.issue(userId, page.getId(), 0)));
    }

    /** Supprime une page du compte, ses versions et son contenu. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        pageService.delete(currentUser.requireId(), id);
        return ResponseEntity.noContent().build();
    }

    /** Supprime toutes les pages du compte à un lieu (la purge proposée à la clôture de mission). */
    @DeleteMapping
    public ResponseEntity<Void> deletePlace(@RequestParam UUID hostId, @RequestParam String space) {
        pageService.deletePlace(currentUser.requireId(), hostId, parseSpace(space));
        return ResponseEntity.noContent().build();
    }

    /** L'archive ZIP des pages du compte à un lieu, écrite au fil de l'eau. */
    @GetMapping("/export")
    public void export(@RequestParam UUID hostId, @RequestParam String space, HttpServletResponse response)
            throws IOException {
        UUID userId = currentUser.requireId();
        PageSpace parsed = parseSpace(space);
        response.setStatus(HttpStatus.OK.value());
        response.setContentType("application/zip");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                .filename("pages-" + parsed.name().toLowerCase(Locale.ROOT) + "-" + java.time.LocalDate.now() + ".zip")
                .build().toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
        pageService.exportPlace(userId, hostId, parsed, response.getOutputStream());
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

    /**
     * Le PDF d'une version d'une page du compte (F-184 / SF-184-02) : A4, thème clair, même charte —
     * imprimé par le service de rendu à partir du lot que la gateway assemble. Les ressources que le
     * moteur n'a pas trouvées sont relayées dans {@code X-Cg-Missing-Resources}.
     *
     * @param version version voulue (courante par défaut)
     */
    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable UUID id, @RequestParam(required = false) Integer version) {
        PagePdfService.PagePdf printed = pagePdfService.print(currentUser.requireId(), id, version);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment()
                .filename(printed.fileName(), java.nio.charset.StandardCharsets.UTF_8).build());
        headers.setCacheControl("private, no-store");
        if (!printed.missing().isBlank()) {
            headers.set("X-Cg-Missing-Resources", printed.missing());
        }
        return new ResponseEntity<>(printed.pdf(), headers, HttpStatus.OK);
    }

    /** L'adresse de lecture d'un ticket, sous le chemin de contexte ({@code /api}). */
    static String viewUrl(HttpServletRequest request, String ticket) {
        return request.getContextPath() + "/p/" + ticket + "/";
    }

    /** {@code titre-de-la-page-v3.html}. */
    static String fileName(Page page, int version) {
        return PageService.slug(page.getTitle()) + "-v" + version + ".html";
    }

    private static PageSpace parseSpace(String raw) {
        try {
            return PageSpace.valueOf(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new PageRejectedException("Espace inconnu : FORGE ou VIGIE.");
        }
    }
}
