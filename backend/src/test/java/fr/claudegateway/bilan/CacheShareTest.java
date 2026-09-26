package fr.claudegateway.bilan;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La part de cache (F-155 / SF-155-06).
 *
 * <p><b>Ce que ces tests ancrent</b> : {@code input_tokens} <b>contient déjà</b> le cache. La
 * formule livrée divisait par {@code input + cacheRead} et rendait donc la moitié de la vraie part.
 * Le test de la session réelle est là pour que personne n'ait à redécouvrir cette sémantique.</p>
 */
class CacheShareTest {

    @Test
    @DisplayName("ANCRAGE — les chiffres réels de la session du 25/09 donnent 86 %, pas 46 %")
    void theRealSessionAnchorsTheSemantics() {
        // Session KPMG, poste CAGIP, 74 tours, 81,70 $ — relevé en production et vérifié au centime
        // contre la grille de tarifs. `input` porte le plein tarif + le cache lu + le cache écrit.
        long input = 42_976_876L;
        long cacheRead = 37_085_919L;
        long cacheWrite = 5_886_951L;

        assertThat(CacheShare.of(input, cacheRead))
                .as("la formule livrée annonçait 46 %% — de quoi croire à un cache froid")
                .isEqualTo(86);

        assertThat(input - cacheRead - cacheWrite)
                .as("ce qui reste au plein tarif : 4 006 jetons sur 43 millions")
                .isEqualTo(4_006L);
    }

    @Test
    @DisplayName("la formule fautive donnait bien la moitié — on garde la trace de l'écart")
    void theOldFormulaHalvedIt() {
        long input = 42_976_876L;
        long cacheRead = 37_085_919L;

        int fautive = (int) Math.round(100.0 * cacheRead / (input + cacheRead));

        assertThat(fautive).isEqualTo(46);
        assertThat(CacheShare.of(input, cacheRead)).isEqualTo(86);
    }

    @Test
    @DisplayName("cas nominal : la moitié de l'entrée vient du cache")
    void half() {
        assertThat(CacheShare.of(1_000, 500)).isEqualTo(50);
    }

    @Test
    @DisplayName("aucun cache, aucune entrée : zéro, sans division par zéro")
    void zeroes() {
        assertThat(CacheShare.of(1_000, 0)).isZero();
        assertThat(CacheShare.of(0, 0)).isZero();
        assertThat(CacheShare.of(0, 500)).isZero();
        assertThat(CacheShare.of(-10, 500)).isZero();
    }

    @Test
    @DisplayName("une donnée aberrante est PLAFONNÉE à 100 — un chiffre que personne ne croit fait douter du reste")
    void cappedAtHundred() {
        assertThat(CacheShare.of(1_000, 9_000)).isEqualTo(100);
    }

    @Test
    @DisplayName("tout en cache : 100 %")
    void fullyCached() {
        assertThat(CacheShare.of(1_000, 1_000)).isEqualTo(100);
    }
}
