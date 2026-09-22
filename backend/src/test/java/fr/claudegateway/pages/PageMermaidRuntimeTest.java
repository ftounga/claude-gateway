package fr.claudegateway.pages;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Le rendu Mermaid injecté à la livraison d'une page (F-142 / SF-142-01). */
class PageMermaidRuntimeTest {

    private static String render(String html) {
        return new String(PageMermaidRuntime.render(html.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("CA1 — un bloc <pre class=\"mermaid\"> déclenche le chargeur cdnjs, le marqueur, et garde le code")
    void injectsRuntimeAndKeepsSource() {
        String source = "<!doctype html><html><body>"
                + "<pre class=\"mermaid\">flowchart TD\n  A[Client]-->B[Gateway]</pre>"
                + "</body></html>";

        String out = render(source);

        assertThat(out)
                .contains(PageMermaidRuntime.MARKER)
                .contains(PageMermaidRuntime.SCRIPT_URL)
                .contains("cdnjs.cloudflare.com")
                // le code Mermaid d'origine reste présent — éditable, jamais seulement rendu
                .contains("flowchart TD")
                .contains("A[Client]-->B[Gateway]");
    }

    @Test
    @DisplayName("CA2 — architecture-beta est détecté et le runtime injecté")
    void architectureBetaIsRendered() {
        String source = "<html><body><pre class=\"mermaid\">architecture-beta\n"
                + "  group api(cloud)[VPC]\n  service db(database)[RDS] in api</pre></body></html>";

        String out = render(source);

        assertThat(out).contains(PageMermaidRuntime.MARKER).contains(PageMermaidRuntime.SCRIPT_URL)
                .contains("architecture-beta");
    }

    @Test
    @DisplayName("CA3 — le runtime porte un repli gracieux (catch + affichage du code)")
    void carriesGracefulFallback() {
        String out = render("<html><body><pre class=\"mermaid\">flowchart TD\nA-->B</pre></body></html>");

        assertThat(out)
                .contains("cg-mermaid-fallback")
                .contains(".catch(")
                .contains("Diagramme invalide")
                // la lib absente (hors ligne) est aussi gérée
                .contains("!window.mermaid");
    }

    @Test
    @DisplayName("CA4 — une page sans Mermaid est servie octet pour octet identique")
    void nonMermaidPageIsUntouched() {
        byte[] source = "<!doctype html><html><body><h1>Rien ici</h1><p>Pas de diagramme.</p></body></html>"
                .getBytes(StandardCharsets.UTF_8);

        byte[] out = PageMermaidRuntime.render(source);

        assertThat(out).isSameAs(source);
    }

    @Test
    @DisplayName("CA5 — deux passages n'injectent qu'un seul runtime (idempotence)")
    void isIdempotent() {
        String once = render("<html><body><pre class=\"mermaid\">flowchart TD\nA-->B</pre></body></html>");
        String twice = render(once);

        assertThat(twice).isEqualTo(once);
        assertThat(twice.split(java.util.regex.Pattern.quote(PageMermaidRuntime.MARKER), -1)).hasSize(2);
    }

    @Test
    @DisplayName("CA6 — l'initialisation est en securityLevel strict (assainissement conservé)")
    void initialisesInStrictMode() {
        String out = render("<html><body><pre class=\"mermaid\">flowchart TD\nA-->B</pre></body></html>");

        assertThat(out).contains("securityLevel").contains("strict").contains("startOnLoad:false");
    }

    @Test
    @DisplayName("un bloc clôturé ```mermaid``` est converti en <pre class=\"mermaid\"> avec le code échappé")
    void convertsFencedBlockAndEscapes() {
        String source = "<html><body>\n```mermaid\nflowchart LR\n  A-->|\"a<b\"|B\n```\n</body></html>";

        String out = render(source);

        assertThat(out).contains("<pre class=\"mermaid\">").contains(PageMermaidRuntime.MARKER)
                // le chevron du libellé est échappé, jamais interprété comme une balise
                .contains("a&lt;b")
                .doesNotContain("```mermaid");
    }

    @Test
    @DisplayName("sans </body>, le runtime est ajouté en fin ; avec </body>, il est inséré avant")
    void insertsBeforeBodyOrAppends() {
        String withBody = render("<html><body><pre class=\"mermaid\">flowchart TD\nA-->B</pre></body></html>");
        assertThat(withBody.indexOf(PageMermaidRuntime.MARKER)).isLessThan(withBody.indexOf("</body>"));

        String noBody = render("<div><pre class=\"mermaid\">flowchart TD\nA-->B</pre></div>");
        assertThat(noBody).contains(PageMermaidRuntime.MARKER);
        assertThat(noBody.indexOf(PageMermaidRuntime.MARKER))
                .isGreaterThan(noBody.indexOf("</pre>"));
    }
}
