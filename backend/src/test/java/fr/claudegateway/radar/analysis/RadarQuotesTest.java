package fr.claudegateway.radar.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** F-101 / SF-101-03 — une citation est toujours tirée du message. */
class RadarQuotesTest {

    private static final String MESSAGE = "Bonjour à tous,\n  la licence n'est   pas signée, donc on attend.";

    @Test
    void verbatimQuoteIsKept() {
        assertThat(RadarQuotes.pick("la licence n'est pas signée", MESSAGE)).isEqualTo("la licence n'est pas signée");
    }

    @Test
    void inventedQuoteIsReplacedByTheMessage() {
        assertThat(RadarQuotes.pick("la licence est signée", MESSAGE))
                .isEqualTo("Bonjour à tous, la licence n'est pas signée, donc on attend.");
        assertThat(RadarQuotes.pick(null, MESSAGE)).startsWith("Bonjour à tous,");
    }

    @Test
    void longMessagesAreTruncated() {
        String quote = RadarQuotes.pick(null, "a ".repeat(400));
        assertThat(quote).hasSize(280).endsWith("…");
    }
}
