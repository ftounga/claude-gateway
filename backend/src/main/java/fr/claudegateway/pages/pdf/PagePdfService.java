package fr.claudegateway.pages.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import fr.claudegateway.pages.PageAttachments;
import fr.claudegateway.pages.PageMermaidRuntime;
import fr.claudegateway.pages.PageService;

/**
 * <b>Le PDF d'une page</b> (F-184 / SF-184-02) : la gateway assemble le <b>lot</b>, le service de rendu
 * l'imprime hors ligne (SF-184-01).
 *
 * <p>Le lot, c'est ce que la page affiche à l'écran : le HTML <b>servi</b> (images inlinées, runtime
 * Mermaid), les pièces jointes de la version, la bibliothèque Mermaid de la gateway, et les scripts et
 * polices externes que la page référence — sur la liste fermée de {@link PageLotScanner}. Même source,
 * même moteur de navigateur : même charte.</p>
 *
 * <p><b>Isolation</b> : tout passe par {@link PageService}, borné au propriétaire. La page d'un autre
 * compte lève {@code PageNotFoundException} <b>avant</b> tout appel au moteur.</p>
 */
@Service
public class PagePdfService {

    /** Bornes du lot : le moteur en accepte 60 ; on garde la marge de la page elle-même. */
    static final int MAX_RESOURCES = 59;
    static final long MAX_LOT_BYTES = 25L * 1024 * 1024;

    private final PageService pageService;
    private final PagePdfRenderer renderer;
    private final ExternalResourceFetcher fetcher;
    private final PagePdfCache cache = new PagePdfCache(PagePdfCache.TTL, PagePdfCache.MAX_ENTRIES,
            PagePdfCache.MAX_BYTES, java.time.Clock.systemUTC());

    public PagePdfService(PageService pageService, PagePdfRenderer renderer, ExternalResourceFetcher fetcher) {
        this.pageService = pageService;
        this.renderer = renderer;
        this.fetcher = fetcher;
    }

    /** Le PDF produit, avec le nom de fichier à proposer, le titre et la version imprimée. */
    public record PagePdf(byte[] pdf, String fileName, String missing, String title, int version) {
    }

    /**
     * Le PDF d'une version d'une page du compte, <b>depuis le cache court</b> s'il vient d'être imprimé
     * (F-184 / SF-184-04 : l'agent imprime, l'utilisateur télécharge aussitôt — sans seconde impression).
     * La version est résolue <b>avant</b> la clé : une nouvelle version n'est jamais servie depuis l'ancienne.
     *
     * @param version version voulue, ou {@code null} / {@code 0} pour la courante
     */
    public PagePdf pdf(UUID userId, UUID pageId, Integer version) {
        // Isolation d'abord : la page d'un autre compte lève PageNotFoundException, cache jamais lu.
        int resolved = pageService.requireVersion(userId, pageId, version);
        String key = userId + "/" + pageId + "/" + resolved;
        return cache.get(key).orElseGet(() -> {
            PagePdf printed = print(userId, pageId, resolved);
            cache.put(key, printed);
            return printed;
        });
    }

    /**
     * Imprime une version d'une page du compte.
     *
     * @param version version voulue, ou {@code null} / {@code 0} pour la courante
     */
    public PagePdf print(UUID userId, UUID pageId, Integer version) {
        PageService.PageContent content = pageService.html(userId, pageId, version);
        String html = new String(content.content(), StandardCharsets.UTF_8);
        Lot lot = new Lot();
        pageService.attachments(userId, pageId, content.version()).forEach((name, bytes) ->
                PageAttachments.contentType(name).ifPresent(type ->
                        lot.add(new PagePdfRenderer.Resource(attachmentUrl(name), type, bytes))));
        if (html.contains(PageMermaidRuntime.SCRIPT_URL)) {
            mermaidLibrary().ifPresent(bytes -> lot.add(new PagePdfRenderer.Resource(
                    PagePdfRenderer.ORIGIN + PageMermaidRuntime.SCRIPT_URL, "text/javascript", bytes)));
        }
        for (URI uri : PageLotScanner.externalReferences(html)) {
            if (lot.full()) {
                break;
            }
            fetcher.fetch(uri).ifPresent(fetched -> {
                lot.add(new PagePdfRenderer.Resource(uri.toString(), fetched.contentType(), fetched.body()));
                if (PageLotScanner.isFontStylesheet(uri)) {
                    String css = new String(fetched.body(), StandardCharsets.UTF_8);
                    for (URI font : PageLotScanner.fontFiles(css)) {
                        if (lot.full()) {
                            break;
                        }
                        fetcher.fetch(font).ifPresent(file -> lot.add(
                                new PagePdfRenderer.Resource(font.toString(), file.contentType(), file.body())));
                    }
                }
            });
        }
        PagePdfRenderer.Printed printed = renderer.print(html, lot.resources);
        String fileName = PageService.slug(content.page().getTitle()) + "-v" + content.version() + ".pdf";
        return new PagePdf(printed.pdf(), fileName, printed.missing(), content.page().getTitle(), content.version());
    }

    /** L'adresse d'une pièce jointe dans le moteur : son nom, résolu sur l'origine virtuelle. */
    static String attachmentUrl(String name) {
        return PagePdfRenderer.ORIGIN + "/" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static Optional<byte[]> mermaidLibrary() {
        ClassPathResource resource = new ClassPathResource("pages/mermaid-" + PageMermaidRuntime.MERMAID_VERSION + ".min.js");
        try (InputStream in = resource.getInputStream()) {
            return Optional.of(in.readAllBytes());
        } catch (IOException e) {
            // Sans la bibliothèque, le runtime affiche son repli (code + message) : le PDF reste produit.
            return Optional.empty();
        }
    }

    /** Le lot en construction, borné en nombre et en octets : au-delà, une ressource est ignorée. */
    private static final class Lot {
        private final List<PagePdfRenderer.Resource> resources = new ArrayList<>();
        private long bytes;

        void add(PagePdfRenderer.Resource resource) {
            if (full() || bytes + resource.body().length > MAX_LOT_BYTES) {
                return;
            }
            resources.add(resource);
            bytes += resource.body().length;
        }

        boolean full() {
            return resources.size() >= MAX_RESOURCES;
        }
    }
}
