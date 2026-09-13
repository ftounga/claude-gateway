package fr.claudegateway.radar;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-99 / SF-99-01 — les normalisations du registre, sans base. */
class RadarTextTest {

    @Test
    @DisplayName("une citation de plus de 280 caractères est tronquée à 280, marque de coupure comprise")
    void longQuoteIsCut() {
        String quote = RadarText.quote("a".repeat(400));

        assertThat(quote).hasSize(RadarEvidence.MAX_QUOTE_LENGTH).endsWith(RadarText.ELLIPSIS);
    }

    @Test
    void shortQuoteIsKeptTrimmed() {
        assertThat(RadarText.quote("  je m'en charge jeudi  ")).isEqualTo("je m'en charge jeudi");
    }

    @Test
    void emptyQuoteIsRefused() {
        assertThatThrownBy(() -> RadarText.quote("   ")).isInstanceOf(InvalidRadarInputException.class);
    }

    @Test
    void requiredTextIsBounded() {
        assertThatThrownBy(() -> RadarText.required("x".repeat(201), 200, "name"))
                .isInstanceOf(InvalidRadarInputException.class);
        assertThatThrownBy(() -> RadarText.required(null, 200, "name"))
                .isInstanceOf(InvalidRadarInputException.class);
        assertThat(RadarText.required(" MFA ", 200, "name")).isEqualTo("MFA");
    }

    @Test
    void optionalTextBecomesNullWhenBlank() {
        assertThat(RadarText.optional("  ", 10, "x")).isNull();
        assertThatThrownBy(() -> RadarText.optional("x".repeat(11), 10, "x"))
                .isInstanceOf(InvalidRadarInputException.class);
    }

    @Test
    @DisplayName("un lien trop long est ignoré, jamais coupé")
    void tooLongLinkIsDropped() {
        assertThat(RadarText.deepLink("https://x/" + "a".repeat(3000))).isNull();
        assertThat(RadarText.deepLink(" https://teams/1 ")).isEqualTo("https://teams/1");
    }

    @Test
    void keyIsLowercasedAndSpacesCollapsed() {
        assertThat(RadarText.key("  Jean.DUPONT@Client.fr ")).isEqualTo("jean.dupont@client.fr");
        assertThat(RadarText.key("La   double  Auth")).isEqualTo("la double auth");
    }
}
