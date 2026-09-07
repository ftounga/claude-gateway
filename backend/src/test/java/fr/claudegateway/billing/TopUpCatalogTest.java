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
    void exposesTheTwoHundredThousandTopUp() {
        assertThat(catalog.find("DAY")).isPresent();
        assertThat(catalog.find("DAY").orElseThrow().tokens()).isEqualTo(200_000L);
    }

    @Test
    void theSmallPackIsNamedAfterWhatItIs() {
        // SF-21-06 : le pack ne s'appelle plus « Pass journée » — c'était l'homonyme du PLAN retiré
        // par SF-09-04, et cette confusion avait masqué des mois durant que ce plan n'avait aucun
        // prix Stripe. Le nom suit désormais le produit Stripe et le reçu du client.
        assertThat(catalog.find("DAY").orElseThrow().label()).isEqualTo("Recharge 200 k tokens");
        // Le CODE, lui, ne bouge pas : il voyage dans les métadonnées des paiements déjà encaissés.
        assertThat(catalog.find("DAY").orElseThrow().code()).isEqualTo("DAY");
    }

    @Test
    void theOneMillionPackIsUntouched() {
        assertThat(catalog.find("STANDARD").orElseThrow().label()).isEqualTo("Recharge — 1 M tokens");
        assertThat(catalog.find("STANDARD").orElseThrow().tokens()).isEqualTo(1_000_000L);
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
