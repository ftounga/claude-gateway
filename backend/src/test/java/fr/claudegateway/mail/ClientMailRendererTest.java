package fr.claudegateway.mail;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Markdown → HTML sobre et texte (F-110 / SF-110-02). */
class ClientMailRendererTest {

    @Test
    void rendersHeadingsListsEmphasisCodeAndTables() {
        ClientMailRenderer.Rendered rendered = ClientMailRenderer.render("""
                # Compte rendu
                - **Décision** : MFA en octobre
                - `terraform apply`

                | Sujet | État |
                |---|---|
                | MFA | ouvert |
                """, "CAGIP");

        assertThat(rendered.html()).contains("<h1>Compte rendu</h1>", "<strong>Décision</strong>",
                "<code>terraform apply</code>", "<table style=", "<td style=\"border:1px solid #E2E8F0;padding:4px 8px\">MFA</td>");
        // Le pied de page a disparu (F-110 / SF-110-06) : il nommait l'outil dans la boîte d'un
        // client. Ce qui est vérifié ici reste le RENDU du corps, qui n'a pas changé.
        assertThat(rendered.html()).doesNotContain("Envoyé depuis", "à votre demande");
        assertThat(rendered.text()).startsWith("# Compte rendu");
        assertThat(rendered.sizeBytes()).isGreaterThan(rendered.html().length());
    }

    @Test
    void escapesRawHtmlAndNeutralizesDangerousLinks() {
        ClientMailRenderer.Rendered rendered = ClientMailRenderer.render(
                "<script>alert(1)</script>\n\n[clic](javascript:alert(1)) <img src=x onerror=alert(1)>", "<b>X</b>");

        assertThat(rendered.html()).doesNotContain("<script>", "javascript:", "<img", "<b>X</b>");
        // Le corps écrit par l'agent reste échappé — c'est la garantie qui compte ici.
        assertThat(rendered.html()).contains("&lt;script&gt;");
        // L'assertion sur « &lt;b&gt;X&lt;/b&gt; » portait sur le NOM DU CLIENT, échappé dans le
        // pied de page. Le pied a disparu (F-110 / SF-110-06), donc le nom du client ne part plus
        // du tout dans le rendu : on le vérifie sous cette forme-là, qui est plus forte.
        assertThat(rendered.html()).doesNotContain("X</b>").doesNotContain("&lt;b&gt;");
    }
}
