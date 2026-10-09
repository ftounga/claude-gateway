package fr.claudegateway.pages.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fr.claudegateway.pages.Page;
import fr.claudegateway.pages.PageService;

/** L'assemblage du lot : pièces jointes, feuille Google Fonts dépliée, bornes (F-184 / SF-184-02). */
class PagePdfServiceTest {

    private final UUID user = UUID.randomUUID();
    private final UUID pageId = UUID.randomUUID();
    private final PageService pages = mock(PageService.class);
    private final PagePdfRenderer renderer = mock(PagePdfRenderer.class);
    private final ExternalResourceFetcher fetcher = mock(ExternalResourceFetcher.class);
    private final PagePdfService service = new PagePdfService(pages, renderer, fetcher);

    private void page(String html, Map<String, byte[]> attachments) {
        Page page = mock(Page.class);
        when(page.getTitle()).thenReturn("Compte rendu — octobre");
        when(pages.html(user, pageId, null)).thenReturn(new PageService.PageContent(
                page, 3, html.getBytes(StandardCharsets.UTF_8), "text/html; charset=utf-8"));
        when(pages.attachments(user, pageId, 3)).thenReturn(attachments);
        when(renderer.print(anyString(), any())).thenReturn(new PagePdfRenderer.Printed(new byte[] {'%'}, ""));
    }

    @SuppressWarnings("unchecked")
    private List<String> lotUrls() {
        ArgumentCaptor<List<PagePdfRenderer.Resource>> lot = ArgumentCaptor.forClass(List.class);
        verify(renderer).print(anyString(), lot.capture());
        return lot.getValue().stream().map(PagePdfRenderer.Resource::url).toList();
    }

    @Test
    @DisplayName("nom de fichier <slug>-v<N>.pdf ; pièce jointe à l'origine virtuelle, nom encodé")
    void fileNameAndAttachments() {
        page("<h1>x</h1>", Map.of("mon schéma.png", new byte[] {1}));

        PagePdfService.PagePdf pdf = service.print(user, pageId, null);

        assertThat(pdf.fileName()).isEqualTo("compte-rendu-octobre-v3.pdf");
        assertThat(lotUrls()).containsExactly("https://page.cg.local/mon%20sch%C3%A9ma.png");
    }

    @Test
    @DisplayName("CA5 — une feuille Google Fonts est jointe ET dépliée en fichiers de police")
    void googleFontsAreUnfolded() {
        page("<link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css2?family=Inter\">", Map.of());
        String css = "/* latin */\n@font-face { src: url(https://fonts.gstatic.com/s/inter/latin.woff2); }";
        when(fetcher.fetch(any())).thenAnswer(inv -> {
            java.net.URI uri = inv.getArgument(0);
            byte[] body = uri.getHost().equals("fonts.googleapis.com") ? css.getBytes(StandardCharsets.UTF_8) : new byte[] {9};
            return Optional.of(new ExternalResourceFetcher.Fetched(uri, "text/css", body));
        });

        service.print(user, pageId, null);

        assertThat(lotUrls()).containsExactly("https://fonts.googleapis.com/css2?family=Inter",
                "https://fonts.gstatic.com/s/inter/latin.woff2");
    }

    @Test
    @DisplayName("CA6 — au-delà de 59 ressources, le lot est borné (le moteur en accepte 60, page comprise)")
    void lotIsBounded() {
        String html = IntStream.range(0, 80)
                .mapToObj(i -> "<script src=\"https://cdn.jsdelivr.net/npm/lib" + i + "@1/x.js\"></script>")
                .collect(Collectors.joining());
        page(html, Map.of());
        when(fetcher.fetch(any())).thenAnswer(inv -> Optional.of(
                new ExternalResourceFetcher.Fetched(inv.getArgument(0), "text/javascript", new byte[] {1})));

        service.print(user, pageId, null);

        assertThat(lotUrls()).hasSize(PagePdfService.MAX_RESOURCES);
    }

    @Test
    @DisplayName("une ressource introuvable n'empêche pas le PDF")
    void missingResourceDoesNotFail() {
        page("<script src=\"https://cdn.jsdelivr.net/npm/a@1/a.js\"></script>", Map.of());
        when(fetcher.fetch(any())).thenReturn(Optional.empty());

        assertThat(service.print(user, pageId, null).pdf()).isNotEmpty();
        verify(renderer).print(anyString(), eq(List.of()));
    }

    @Test
    @DisplayName("SF-184-04 CA5 — un second appel identique sert le cache : une seule impression")
    void secondCallIsServedFromTheCache() {
        page("<h1>x</h1>", Map.of());
        when(pages.requireVersion(user, pageId, null)).thenReturn(3);
        PageService.PageContent content = pages.html(user, pageId, null);
        when(pages.html(user, pageId, 3)).thenReturn(content);

        PagePdfService.PagePdf first = service.pdf(user, pageId, null);
        PagePdfService.PagePdf second = service.pdf(user, pageId, null);

        assertThat(second).isSameAs(first);
        assertThat(first.title()).isEqualTo("Compte rendu — octobre");
        assertThat(first.version()).isEqualTo(3);
        verify(renderer, org.mockito.Mockito.times(1)).print(anyString(), any());
    }

    @Test
    @DisplayName("SF-184-04 — isolation : la version est vérifiée AVANT le cache (page d'autrui → 404)")
    void isolationBeforeCache() {
        when(pages.requireVersion(user, pageId, null)).thenThrow(new fr.claudegateway.pages.PageNotFoundException());
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.pdf(user, pageId, null))
                .isInstanceOf(fr.claudegateway.pages.PageNotFoundException.class);
        verify(renderer, org.mockito.Mockito.never()).print(anyString(), any());
    }
}
