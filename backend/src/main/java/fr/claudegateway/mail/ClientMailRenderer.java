package fr.claudegateway.mail;

import java.util.List;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.web.util.HtmlUtils;

/**
 * <b>Markdown → HTML sobre, et sa version texte</b> (F-110 / SF-110-02, cadrage §4).
 *
 * <p>Le corps vient d'un modèle : le HTML brut qu'il contiendrait est <b>échappé</b> (jamais interprété), et les
 * liens sont <b>assainis</b> ({@code javascript:} et consorts neutralisés). La mise en forme est portée par des
 * styles en ligne — les messageries d'entreprise retirent les feuilles de style — et reste sobre : une police
 * lisible, des tableaux bordés, un pied qui dit d'où vient le courriel.</p>
 */
public final class ClientMailRenderer {

    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer HTML = HtmlRenderer.builder()
            .extensions(EXTENSIONS)
            .escapeHtml(true)
            .sanitizeUrls(true)
            .build();

    /** Un courriel rendu. */
    public record Rendered(String text, String html) {

        /** Taille en octets des deux versions (UTF-8) : ce que le journal retient. */
        public int sizeBytes() {
            return text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    + html.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        }
    }

    private ClientMailRenderer() {
    }

    /**
     * Rend un corps Markdown.
     *
     * @param markdown   corps écrit par l'agent
     * @param clientName nom du client, cité dans le pied
     * @return versions texte et HTML
     */
    public static Rendered render(String markdown, String clientName) {
        String source = markdown == null ? "" : markdown;
        String footer = "Envoyé depuis claude-gateway pour " + clientName + ", à votre demande.";
        String body = HTML.render(PARSER.parse(source));
        String styled = body
                .replace("<table>", "<table style=\"border-collapse:collapse;margin:8px 0\">")
                .replace("<th>", "<th style=\"border:1px solid #E2E8F0;padding:4px 8px;text-align:left\">")
                .replace("<td>", "<td style=\"border:1px solid #E2E8F0;padding:4px 8px\">")
                .replace("<pre>", "<pre style=\"background:#F5F6FA;padding:8px;overflow:auto\">");
        String html = "<!doctype html><html><body>"
                + "<div style=\"font-family:Inter,Arial,Helvetica,sans-serif;font-size:14px;line-height:1.5;"
                + "color:#0F172A;max-width:760px\">"
                + styled
                + "<hr style=\"border:none;border-top:1px solid #E2E8F0;margin:24px 0 8px\">"
                + "<p style=\"font-size:12px;color:#64748B\">" + HtmlUtils.htmlEscape(footer, "UTF-8") + "</p>"
                + "</div></body></html>";
        String text = source.strip() + "\n\n-- \n" + footer + "\n";
        return new Rendered(text, html);
    }
}
