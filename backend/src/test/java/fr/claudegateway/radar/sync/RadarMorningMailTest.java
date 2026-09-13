package fr.claudegateway.radar.sync;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import fr.claudegateway.mail.ClientMailRenderer;
import fr.claudegateway.mail.ResolvedRecipient;
import fr.claudegateway.radar.dto.RadarBoardViews.BriefCounts;
import fr.claudegateway.radar.dto.RadarBoardViews.BriefKind;
import fr.claudegateway.radar.dto.RadarBoardViews.BriefSentence;
import fr.claudegateway.radar.dto.RadarBoardViews.BriefView;

/** Le corps du résumé du matin par courriel (F-110 / SF-110-04). */
class RadarMorningMailTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-09-14T06:00:00Z");
    private static final ResolvedRecipient VERIFIED = new ResolvedRecipient("franck@cagip.fr", true, "CAGIP");

    private static BriefView brief(List<BriefSentence> sentences, String warning) {
        return new BriefView(NOW, NOW.minusHours(24), sentences, new BriefCounts(3, 2, 1, 12, 1, 5), null, null,
                warning == null, warning, List.of());
    }

    @Test
    void composesSentencesCountsFollowUpsAndTheLink() {
        String body = RadarMorningMail.compose(
                brief(List.of(new BriefSentence(BriefKind.FOLLOW_UP, "Relance due : Julie — « PV du COPIL ».", null, null)),
                        null),
                List.of("Julie Martin — « PV du COPIL »", "Marc — « accès VPN »"), VERIFIED,
                "https://www.ng-itconsulting.com/vigie/h1");

        assertThat(body).startsWith("# Résumé du matin — CAGIP");
        assertThat(body).contains("- Relance due : Julie — « PV du COPIL ».", "| 5 | 3 | 2 | 1 | 12 | 1 |",
                "- Julie Martin — « PV du COPIL »", "- Marc — « accès VPN »",
                "[Ouvrir la Vigie](https://www.ng-itconsulting.com/vigie/h1)");
        assertThat(body).doesNotContain("Aucune adresse vérifiée");

        String html = ClientMailRenderer.render(body, "CAGIP").html();
        assertThat(html).contains("<h1>Résumé du matin — CAGIP</h1>", "<table", "href=\"https://www.ng-itconsulting.com/vigie/h1\"");
    }

    @Test
    void saysTheCalmTheWarningAndTheFallback() {
        String body = RadarMorningMail.compose(brief(List.of(), "Synchro partielle : 2 fils non lus."), List.of(),
                new ResolvedRecipient("ntounga@gmail.com", false, "CAGIP"), "https://x/vigie/h1");

        assertThat(body).contains("> Synchro partielle : 2 fils non lus.", "Rien de nouveau depuis hier.",
                "Aucune relance due.", "Aucune adresse vérifiée pour CAGIP : ce résumé arrive à l'adresse de votre compte.");
    }

    @Test
    void registryTextCannotInjectMarkdown() {
        String body = RadarMorningMail.compose(
                brief(List.of(new BriefSentence(BriefKind.MOVED, "Voir [ici](javascript:alert(1)) <b>x</b> | # titre", null,
                        null)), null),
                List.of("*gras* _souligné_"), VERIFIED, "https://x/vigie/h1");

        String html = ClientMailRenderer.render(body, "CAGIP").html();
        assertThat(html).doesNotContain("href=\"javascript", "<b>x</b>", "<em>", "<strong>gras");
    }
}
