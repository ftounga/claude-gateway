package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-99 / SF-99-05 — l'écriture Markdown de l'export, sans base. */
class RadarMarkdownTest {

    @Test
    @DisplayName("le Markdown d'une citation ne s'interprète pas")
    void sourceTextIsEscaped() {
        assertThat(RadarMarkdown.text("**urgent** [clic](http://x) <b>#1</b> _a_"))
                .isEqualTo("\\*\\*urgent\\*\\* \\[clic\\](http://x) \\<b\\>\\#1\\</b\\> \\_a\\_");
        assertThat(RadarMarkdown.text("ligne 1\nligne 2")).isEqualTo("ligne 1 ligne 2");
        assertThat(RadarMarkdown.text(null)).isEmpty();
    }

    @Test
    @DisplayName("seuls les liens http(s) sont rendus, et sans casser la syntaxe")
    void onlyHttpLinks() {
        assertThat(RadarMarkdown.link("javascript:alert(1)")).isEmpty();
        assertThat(RadarMarkdown.link("https://teams.microsoft.com/l/message/1 (x)"))
                .isEqualTo("[lien](https://teams.microsoft.com/l/message/1%20%28x%29)");
        assertThat(RadarMarkdown.link(null)).isEmpty();
    }

    @Test
    void fileNameIsAsciiSlug() {
        assertThat(RadarMarkdown.fileName("EDENRED — Poste été", LocalDate.of(2026, 9, 13)))
                .isEqualTo("radar-edenred-poste-ete-2026-09-13.md");
        assertThat(RadarMarkdown.fileName("   ", LocalDate.of(2026, 9, 13))).isEqualTo("radar-poste-2026-09-13.md");
    }
}
