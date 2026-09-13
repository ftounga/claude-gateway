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
        assertThat(rendered.html()).contains("Envoyé depuis claude-gateway pour CAGIP, à votre demande.");
        assertThat(rendered.text()).startsWith("# Compte rendu").contains("pour CAGIP");
        assertThat(rendered.sizeBytes()).isGreaterThan(rendered.html().length());
    }

    @Test
    void escapesRawHtmlAndNeutralizesDangerousLinks() {
        ClientMailRenderer.Rendered rendered = ClientMailRenderer.render(
                "<script>alert(1)</script>\n\n[clic](javascript:alert(1)) <img src=x onerror=alert(1)>", "<b>X</b>");

        assertThat(rendered.html()).doesNotContain("<script>", "javascript:", "<img", "<b>X</b>");
        assertThat(rendered.html()).contains("&lt;script&gt;", "&lt;b&gt;X&lt;/b&gt;");
    }
}
