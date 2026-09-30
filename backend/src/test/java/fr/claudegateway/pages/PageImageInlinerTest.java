package fr.claudegateway.pages;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L'embarquement des images d'une page à la livraison (F-142 / SF-142-22). */
class PageImageInlinerTest {

    private static final byte[] SVG = ("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">"
            + "<rect width=\"10\" height=\"10\"/></svg>").getBytes(StandardCharsets.UTF_8);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3};

    private static Function<String, Optional<byte[]>> resolver(Map<String, byte[]> files) {
        return name -> Optional.ofNullable(files.get(name));
    }

    private static String inline(String html, Map<String, byte[]> files) {
        return PageImageInliner.inline(html, resolver(files));
    }

    @Test
    @DisplayName("CA2 — <img src=\"diagram.svg\"> devient data:image/svg+xml, le lien « ouvrir en grand » reste relatif")
    void inlinesSvgAndKeepsOpenLargeLink() {
        String source = "<html><body>"
                + "<a href=\"diagram.svg\" target=\"_blank\">ouvrir en grand</a>"
                + "<img src=\"diagram.svg\" width=\"100%\" alt=\"archi\">"
                + "</body></html>";

        String out = inline(source, Map.of("diagram.svg", SVG));

        String expected = "data:image/svg+xml;base64," + Base64.getEncoder().encodeToString(SVG);
        assertThat(out)
                // le diagramme est embarqué en data: (plus en src de fichier nu)
                .contains("<img src=\"" + expected + "\" width=\"100%\" alt=\"archi\">")
                .doesNotContain("src=\"diagram.svg\"")
                // le lien « ouvrir en grand » vers la pièce jointe est CONSERVÉ, relatif
                .contains("<a href=\"diagram.svg\" target=\"_blank\">ouvrir en grand</a>");
    }

    @Test
    @DisplayName("CA3 — le motif hérité (PNG) est réparé à la livraison : embarqué en data:image/png")
    void inlinesLegacyPng() {
        String out = inline("<img src='agenor-avant.png'>", Map.of("agenor-avant.png", PNG));

        assertThat(out).isEqualTo("<img src='data:image/png;base64,"
                + Base64.getEncoder().encodeToString(PNG) + "'>");
    }

    @Test
    @DisplayName("CA4 — idempotent : un src déjà en data: (ou une URL, ou un chemin) est laissé intact")
    void leavesDataUrlsAndPathsUntouched() {
        Map<String, byte[]> files = Map.of("diagram.svg", SVG);

        assertThat(inline("<img src=\"data:image/svg+xml;base64,PHN2Zy8+\">", files))
                .isEqualTo("<img src=\"data:image/svg+xml;base64,PHN2Zy8+\">");
        assertThat(inline("<img src=\"https://cdn/x.png\">", files))
                .isEqualTo("<img src=\"https://cdn/x.png\">");
        assertThat(inline("<img src=\"files/diagram.svg\">", files))
                .isEqualTo("<img src=\"files/diagram.svg\">");
        assertThat(inline("<img src=\"../diagram.svg\">", files))
                .isEqualTo("<img src=\"../diagram.svg\">");
    }

    @Test
    @DisplayName("CA4 — une page sans <img> embarquable est rendue octet pour octet identique")
    void nonImagePageIsUntouched() {
        byte[] source = ("<!doctype html><html><body><h1>Rien ici</h1>"
                + "<pre class=\"mermaid\">flowchart TD\nA-->B</pre></body></html>").getBytes(StandardCharsets.UTF_8);

        byte[] out = PageImageInliner.inline(source, resolver(Map.of("diagram.svg", SVG)));

        assertThat(out).isSameAs(source);
    }

    @Test
    @DisplayName("une pièce jointe non-image (css/js/json) référencée en <img> n'est jamais embarquée")
    void doesNotInlineNonImageAttachment() {
        // Cas artificiel mais probant : seul image/* est embarqué, jamais du texte.
        String out = inline("<img src=\"data.json\">", Map.of("data.json", "{}".getBytes(StandardCharsets.UTF_8)));

        assertThat(out).isEqualTo("<img src=\"data.json\">");
    }

    @Test
    @DisplayName("un <img> vers une pièce jointe absente de la version reste inchangé (aucune invention)")
    void leavesMissingAttachmentUntouched() {
        String out = inline("<img src=\"absente.svg\">", Map.of("presente.svg", SVG));

        assertThat(out).isEqualTo("<img src=\"absente.svg\">");
    }

    @Test
    @DisplayName("plusieurs <img> sont embarqués, un <a href> homonyme n'est jamais touché")
    void inlinesEveryImgButNeverAnchors() {
        String source = "<a href=\"a.svg\">lien</a><img src=\"a.svg\"><img src=\"b.png\">";

        String out = inline(source, Map.of("a.svg", SVG, "b.png", PNG));

        assertThat(out)
                .contains("<a href=\"a.svg\">lien</a>")
                .contains("data:image/svg+xml;base64,")
                .contains("data:image/png;base64,")
                .doesNotContain("src=\"a.svg\"")
                .doesNotContain("src=\"b.png\"");
    }
}
