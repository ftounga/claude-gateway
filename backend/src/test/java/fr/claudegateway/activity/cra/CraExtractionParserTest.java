package fr.claudegateway.activity.cra;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Le parseur tolérant de la sortie du modèle (F-124 / SF-124-03). */
class CraExtractionParserTest {

    private final CraExtractionParser parser = new CraExtractionParser();

    @Test
    void parsesAPlainJsonArray() {
        List<CraExtraction> lines = parser.parse(
                "[{\"client\":\"Free\",\"days\":20,\"month\":\"2025-09\"},"
                        + "{\"client\":\"KG\",\"days\":13,\"month\":\"2025-09\"}]");
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0).client()).isEqualTo("Free");
        assertThat(lines.get(0).days()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(lines.get(0).month()).isEqualTo("2025-09");
        assertThat(lines.get(1).client()).isEqualTo("KG");
    }

    @Test
    void isTolerantOfSurroundingTextAndCodeFences() {
        List<CraExtraction> lines = parser.parse(
                "Voici ce que j'ai compris :\n```json\n[{\"client\":\"Eden Red\",\"days\":0.5}]\n```\nVoilà.");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).client()).isEqualTo("Eden Red");
        assertThat(lines.get(0).days()).isEqualByComparingTo(new BigDecimal("0.5"));
        assertThat(lines.get(0).month()).isNull(); // pas de mois → la Gateway mettra le mois courant
    }

    @Test
    void returnsEmptyOnGarbageOrEmptyArray() {
        assertThat(parser.parse("désolé, je n'ai rien compris")).isEmpty();
        assertThat(parser.parse("[]")).isEmpty();
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("{not an array}")).isEmpty();
    }

    @Test
    void acceptsDaysAsStringAndFrenchDecimalComma() {
        List<CraExtraction> lines = parser.parse("[{\"client\":\"X\",\"days\":\"1,5\"}]");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).days()).isEqualByComparingTo(new BigDecimal("1.5"));
    }

    @Test
    void skipsEntriesWithoutAClientName() {
        List<CraExtraction> lines = parser.parse("[{\"days\":10},{\"client\":\"Free\",\"days\":5}]");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).client()).isEqualTo("Free");
    }

    @Test
    void ignoresAMalformedMonth() {
        List<CraExtraction> lines = parser.parse("[{\"client\":\"Free\",\"days\":5,\"month\":\"sept\"}]");
        assertThat(lines.get(0).month()).isNull();
    }
}
