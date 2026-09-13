package fr.claudegateway.pages;

import java.util.Optional;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>La seule route des pages ouverte sans authentification</b> (F-109 / SF-109-01, cadrage §3 et §9).
 *
 * <p>Elle sert une page à partir d'un <b>jeton</b> porté dans l'adresse — un ticket de lecture de l'écran
 * (SF-109-01) et, à partir de SF-109-05, un lien de partage — et <b>rien d'autre</b> : aucun verbe que
 * {@code GET}, aucune donnée que le contenu de la page désignée par le jeton. Toute réponse, erreur
 * comprise, porte la politique de {@link PageContentPolicy}.</p>
 *
 * <p>La page est servie sous {@code /p/{jeton}/} — la barre finale est ce qui fait résoudre une pièce
 * jointe relative ({@code logo.svg}) en {@code /p/{jeton}/logo.svg}.</p>
 */
@RestController
@RequestMapping("/p")
public class PagePublicController {

    private final PageService pageService;
    private final PageViewTicketService tickets;

    public PagePublicController(PageService pageService, PageViewTicketService tickets) {
        this.pageService = pageService;
        this.tickets = tickets;
    }

    /** Sans barre finale : redirection relative, pour que les pièces jointes relatives se résolvent. */
    @GetMapping("/{token}")
    public ResponseEntity<Void> withoutTrailingSlash(@PathVariable String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.LOCATION, token + "/");
        return new ResponseEntity<>(headers, HttpStatus.FOUND);
    }

    /** Le HTML de la page désignée par le jeton. */
    @GetMapping("/{token}/")
    public ResponseEntity<byte[]> page(@PathVariable String token) {
        return resolve(token)
                .flatMap(ticket -> {
                    try {
                        return Optional.of(pageService.html(ticket.userId(), ticket.pageId(), ticket.version()));
                    } catch (PageNotFoundException e) {
                        return Optional.empty();
                    }
                })
                .map(content -> PageContentPolicy.ok(content.content(), content.contentType()))
                .orElseGet(PageContentPolicy::notFound);
    }

    /** Une pièce jointe de la page désignée par le jeton. */
    @GetMapping("/{token}/{name}")
    public ResponseEntity<byte[]> attachment(@PathVariable String token, @PathVariable String name) {
        return resolve(token)
                .flatMap(ticket -> pageService.attachment(ticket.userId(), ticket.pageId(), ticket.version(), name))
                .map(content -> PageContentPolicy.ok(content.content(), content.contentType()))
                .orElseGet(PageContentPolicy::notFound);
    }

    private Optional<PageViewTicketService.Ticket> resolve(String token) {
        return tickets.read(token);
    }
}
