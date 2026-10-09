package fr.claudegateway.pages.pdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Ce qu'une page va chercher dehors — liste fermée, HTTPS seulement (F-184 / SF-184-02). */
class PageLotScannerTest {

    @Test
    @DisplayName("script, link et @import sur la liste fermée sont relevés, dans l'ordre, sans doublon")
    void collectsAllowedReferences() {
        String html = "<script src=\"https://cdnjs.cloudflare.com/ajax/libs/d3/7.9.0/d3.min.js\"></script>"
                + "<script defer src='https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js'></script>"
                + "<link rel=\"stylesheet\" href=\"https://fonts.googleapis.com/css2?family=Inter:wght@400;600&amp;display=swap\">"
                + "<style>@import url('https://fonts.googleapis.com/css2?family=Space+Grotesk');</style>"
                + "<script src=\"https://cdnjs.cloudflare.com/ajax/libs/d3/7.9.0/d3.min.js#again\"></script>";

        assertThat(PageLotScanner.externalReferences(html)).extracting(URI::toString).containsExactly(
                "https://cdnjs.cloudflare.com/ajax/libs/d3/7.9.0/d3.min.js",
                "https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js",
                "https://fonts.googleapis.com/css2?family=Inter:wght@400;600&display=swap",
                "https://fonts.googleapis.com/css2?family=Space+Grotesk");
    }

    @Test
    @DisplayName("CA4 — hors liste, en clair, adresse interne, identifiants ou port exotique : ignorés")
    void refusesEverythingElse() {
        String html = "<script src=\"https://evil.example/x.js\"></script>"
                + "<script src=\"http://cdn.jsdelivr.net/npm/a.js\"></script>"
                + "<script src=\"https://169.254.169.254/latest/meta-data\"></script>"
                + "<script src=\"https://localhost/x.js\"></script>"
                + "<script src=\"https://user:pw@cdn.jsdelivr.net/npm/a.js\"></script>"
                + "<script src=\"https://cdn.jsdelivr.net:8443/npm/a.js\"></script>"
                + "<script src=\"https://cdn.jsdelivr.net.evil.example/a.js\"></script>"
                + "<script src=\"lib.js\"></script>";

        assertThat(PageLotScanner.externalReferences(html)).isEmpty();
    }

    @Test
    @DisplayName("// sans schéma : lu comme HTTPS")
    void protocolRelative() {
        assertThat(PageLotScanner.externalReferences("<script src=\"//cdn.jsdelivr.net/npm/a@1/a.js\"></script>"))
                .extracting(URI::toString).containsExactly("https://cdn.jsdelivr.net/npm/a@1/a.js");
    }

    @Test
    @DisplayName("CA5 — feuille Google Fonts : seuls latin et latin-ext, seulement sur fonts.gstatic.com")
    void fontFilesOfTheLatinSubsets() {
        String css = "/* cyrillic */\n@font-face { font-family: 'Inter'; src: url(https://fonts.gstatic.com/s/inter/cyr.woff2) format('woff2'); }\n"
                + "/* latin-ext */\n@font-face { font-family: 'Inter'; src: url(https://fonts.gstatic.com/s/inter/ext.woff2) format('woff2'); }\n"
                + "/* latin */\n@font-face { font-family: 'Inter'; src: url(https://fonts.gstatic.com/s/inter/latin.woff2) format('woff2'); }\n"
                + "/* latin */\n@font-face { font-family: 'X'; src: url(https://evil.example/f.woff2) format('woff2'); }\n";

        List<URI> files = PageLotScanner.fontFiles(css);

        assertThat(files).extracting(URI::toString).containsExactly(
                "https://fonts.gstatic.com/s/inter/ext.woff2", "https://fonts.gstatic.com/s/inter/latin.woff2");
        assertThat(PageLotScanner.isFontStylesheet(URI.create("https://fonts.googleapis.com/css2?family=Inter"))).isTrue();
        assertThat(PageLotScanner.isFontStylesheet(URI.create("https://cdn.jsdelivr.net/npm/a.js"))).isFalse();
    }

    @Test
    @DisplayName("HTML vide ou absent : rien")
    void empty() {
        assertThat(PageLotScanner.externalReferences(null)).isEmpty();
        assertThat(PageLotScanner.fontFiles("")).isEmpty();
    }
}
