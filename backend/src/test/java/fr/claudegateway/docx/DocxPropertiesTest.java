package fr.claudegateway.docx;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Les bornes anti zip-bomb ont des valeurs par défaut, et une configuration absurde ne peut pas les
 * désarmer (F-86 / SF-86-01). Un {@code 0} laissé dans un fichier de configuration ne doit pas
 * ouvrir la porte : il retombe sur la valeur par défaut.
 */
class DocxPropertiesTest {

    @Test
    void fallsBackToDefaultsWhenNothingIsConfigured() {
        DocxProperties properties = new DocxProperties(null, null, null);

        assertThat(properties.maxEntries()).isEqualTo(2048);
        assertThat(properties.maxEntryBytes()).isEqualTo(32L * 1024 * 1024);
        assertThat(properties.maxTotalBytes()).isEqualTo(256L * 1024 * 1024);
    }

    @Test
    void refusesToBeDisarmedByZeroOrNegativeValues() {
        DocxProperties zeroed = new DocxProperties(0, 0L, 0L);
        DocxProperties negative = new DocxProperties(-1, -1L, -1L);

        assertThat(zeroed.maxEntries()).isEqualTo(2048);
        assertThat(zeroed.maxEntryBytes()).isPositive();
        assertThat(zeroed.maxTotalBytes()).isPositive();
        assertThat(negative.maxEntries()).isEqualTo(2048);
        assertThat(negative.maxEntryBytes()).isPositive();
        assertThat(negative.maxTotalBytes()).isPositive();
    }

    @Test
    void keepsExplicitValues() {
        DocxProperties properties = new DocxProperties(8, 1024L, 4096L);

        assertThat(properties.maxEntries()).isEqualTo(8);
        assertThat(properties.maxEntryBytes()).isEqualTo(1024L);
        assertThat(properties.maxTotalBytes()).isEqualTo(4096L);
    }
}
