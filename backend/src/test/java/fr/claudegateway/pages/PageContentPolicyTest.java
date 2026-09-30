package fr.claudegateway.pages;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/** La politique d'une page servie (F-109 / SF-109-01, cadrage §3 — non négociable). */
class PageContentPolicyTest {

    @Test
    @DisplayName("§3.1 — origine opaque : scripts et fenêtres, et RIEN d'autre dans le bac à sable")
    void opaqueOrigin() {
        assertThat(PageContentPolicy.CSP).startsWith("sandbox allow-scripts allow-popups;");
        assertThat(PageContentPolicy.CSP)
                .doesNotContain("allow-same-origin")
                .doesNotContain("allow-forms")
                .doesNotContain("allow-top-navigation")
                .doesNotContain("allow-modals")
                .doesNotContain("allow-popups-to-escape-sandbox");
    }

    @Test
    @DisplayName("§3.2 — aucune sortie réseau hors de la liste close")
    void noNetwork() {
        assertThat(PageContentPolicy.CSP)
                .contains("default-src 'none'")
                .contains("connect-src 'none'")
                .contains("form-action 'none'")
                .contains("frame-src 'none'")
                .contains("worker-src 'none'")
                .contains("object-src 'none'")
                .contains("base-uri 'none'")
                .contains("script-src 'unsafe-inline' https://cdnjs.cloudflare.com https://cdn.jsdelivr.net 'self'")
                .contains("font-src https://fonts.gstatic.com data: 'self'")
                .contains("img-src data: blob: 'self'");
        // Aucun joker, aucun schéma ouvert : une page ne peut pas charger « n'importe quoi en https ».
        assertThat(PageContentPolicy.CSP).doesNotContain("*").doesNotContain("https:;").doesNotContain(" https: ")
                .doesNotContain("'unsafe-eval'");
    }

    @Test
    @DisplayName("SF-142-22 — data: s'affiche inline, une pièce jointe 'self' est bloquée (origine opaque)")
    void inlineImageIsAllowedOnlyAsDataUri() {
        // La page est en origine opaque : c'est le fait qui bloque 'self'. Un garde-fou : si quelqu'un
        // ajoutait allow-same-origin, cette assertion tombe et la preuve du correctif avec.
        assertThat(PageContentPolicy.isOpaqueOrigin()).isTrue();

        // data: — le SEUL schéma d'image qui s'affiche : img-src le porte, indépendamment de l'origine.
        assertThat(PageContentPolicy.allowsInlineImage("data:image/svg+xml;base64,PHN2Zy8+")).isTrue();
        assertThat(PageContentPolicy.allowsInlineImage("data:image/png;base64,iVBORw0KGgo=")).isTrue();

        // Une pièce jointe relative ('self') est BLOQUÉE inline — c'est exactement le symptôme constaté
        // (le diagramme n'apparaît pas dans la page), PNG hérité comme SVG neuf.
        assertThat(PageContentPolicy.allowsInlineImage("agenor-avant.svg")).isFalse();
        assertThat(PageContentPolicy.allowsInlineImage("agenor-avant.png")).isFalse();
        assertThat(PageContentPolicy.allowsInlineImage("agenor-avant-v2.svg")).isFalse();

        // blob: (fetch impossible, connect-src 'none') et http(s) (hors liste close) : bloqués aussi.
        assertThat(PageContentPolicy.allowsInlineImage("blob:https://x/abc")).isFalse();
        assertThat(PageContentPolicy.allowsInlineImage("https://cdn.example/x.svg")).isFalse();
        assertThat(PageContentPolicy.allowsInlineImage("")).isFalse();
        assertThat(PageContentPolicy.allowsInlineImage(null)).isFalse();

        // La CSP servie est INCHANGÉE : img-src reste 'data: blob: 'self'', le bac à sable sans same-origin.
        assertThat(PageContentPolicy.CSP).contains("img-src data: blob: 'self'")
                .doesNotContain("allow-same-origin");
    }

    @Test
    @DisplayName("les en-têtes compagnons sont posés")
    void companionHeaders() {
        HttpHeaders headers = PageContentPolicy.headers("text/html; charset=utf-8");

        assertThat(headers.getFirst("Content-Security-Policy")).isEqualTo(PageContentPolicy.CSP);
        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("Referrer-Policy")).isEqualTo("no-referrer");
        assertThat(headers.getFirst(HttpHeaders.CACHE_CONTROL)).isEqualTo("private, no-store");
        assertThat(headers.getFirst("Cross-Origin-Resource-Policy")).isEqualTo("same-origin");
        assertThat(headers.getFirst("Permissions-Policy")).contains("camera=()").contains("microphone=()");
    }
}
