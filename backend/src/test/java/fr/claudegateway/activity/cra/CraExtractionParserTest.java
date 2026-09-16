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

    // ------------------------------------------------------------------ plages (SF-124-04)

    @Test
    void parsesAPresetRange() {
        List<CraExtraction> lines = parser.parse(
                "[{\"client\":\"Free\",\"range\":{\"preset\":\"FULL_MONTH\"},\"month\":\"2025-08\"}]");
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).days()).isNull();
        assertThat(lines.get(0).range()).isNotNull();
        assertThat(lines.get(0).range().preset()).isEqualTo("FULL_MONTH");
    }

    @Test
    void parsesFromDayToEndOfMonth() {
        List<CraExtraction> lines = parser.parse(
                "[{\"client\":\"KG\",\"range\":{\"fromDay\":10},\"month\":\"2025-08\"}]");
        assertThat(lines.get(0).range().fromDay()).isEqualTo(10);
        assertThat(lines.get(0).range().toDay()).isNull();
        assertThat(lines.get(0).range().preset()).isNull();
    }

    @Test
    void parsesADayToDayRange() {
        List<CraExtraction> lines = parser.parse(
                "[{\"client\":\"KG\",\"range\":{\"fromDay\":10,\"toDay\":20}}]");
        assertThat(lines.get(0).range().fromDay()).isEqualTo(10);
        assertThat(lines.get(0).range().toDay()).isEqualTo(20);
    }

    @Test
    void parsesIsoDatesInRange() {
        List<CraExtraction> lines = parser.parse(
                "[{\"client\":\"Free\",\"range\":{\"from\":\"2025-08-10\",\"to\":\"2025-08-31\"}}]");
        assertThat(lines.get(0).range().from()).isEqualTo(java.time.LocalDate.of(2025, 8, 10));
        assertThat(lines.get(0).range().to()).isEqualTo(java.time.LocalDate.of(2025, 8, 31));
    }

    @Test
    void normalisesPresetSynonymsAndIgnoresUnknownOnes() {
        assertThat(parser.parse("[{\"client\":\"Free\",\"range\":{\"preset\":\"whole month\"}}]")
                .get(0).range().preset()).isEqualTo("FULL_MONTH");
        // Un preset inconnu, seul, ne fait pas une plage exploitable → range null.
        assertThat(parser.parse("[{\"client\":\"Free\",\"range\":{\"preset\":\"someday\"}}]")
                .get(0).range()).isNull();
    }

    @Test
    void daysWinOverRange_backwardCompatible() {
        List<CraExtraction> lines = parser.parse("[{\"client\":\"Free\",\"days\":20}]");
        assertThat(lines.get(0).days()).isEqualByComparingTo(new BigDecimal("20"));
        assertThat(lines.get(0).range()).isNull();
    }
}
