package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Tests unitaires du catalogue de packs de tokens (SF-21-02). */
class TopUpCatalogTest {

    private final TopUpCatalog catalog = new TopUpCatalog();

    @Test
    void exposesAtLeastTheStandardPack() {
        assertThat(catalog.packs()).isNotEmpty();
        assertThat(catalog.find("STANDARD")).isPresent();
        assertThat(catalog.find("STANDARD").orElseThrow().tokens()).isPositive();
    }

    @Test
    void exposesTheDayPass() {
        assertThat(catalog.find("DAY")).isPresent();
        assertThat(catalog.find("DAY").orElseThrow().tokens()).isEqualTo(200_000L);
    }

    @Test
    void findIsCaseInsensitiveAndTrims() {
        assertThat(catalog.find("  standard  ")).isPresent();
        assertThat(catalog.find("Standard")).isPresent();
    }

    @Test
    void findReturnsEmptyForUnknownOrBlank() {
        assertThat(catalog.find("GHOST")).isEmpty();
        assertThat(catalog.find("")).isEmpty();
        assertThat(catalog.find(null)).isEmpty();
    }
}
