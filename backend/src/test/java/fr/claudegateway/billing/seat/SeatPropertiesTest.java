package fr.claudegateway.billing.seat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import fr.claudegateway.billing.seat.SeatProperties.QuotaTier;

/**
 * La configuration du supplément par poste (F-65 / SF-65-01).
 *
 * <p>Ce qui s'y vérifie n'est pas une valeur commerciale — il n'y en a aucune dans le code — mais
 * que les <b>défauts rendent le mécanisme inerte</b> : sans configuration, aucun jeton n'est apporté
 * et rien n'est facturé. C'est la garantie que livrer F-65 ne change le quota de personne.</p>
 */
class SeatPropertiesTest {

    @Test
    void defaultsLeaveTheMechanismInert() {
        SeatProperties properties = new SeatProperties(null, null, null, null, null, null);

        assertThat(properties.includedSeats()).isEqualTo(1);
        assertThat(properties.tokensPerExtraSeat()).isZero();
        assertThat(properties.quotaTiers()).isEmpty();
        assertThat(properties.proration()).isEqualTo(SeatProration.DAILY);
        assertThat(properties.isBilled()).isFalse();
        assertThat(properties.displayPrice()).isEmpty();
        assertThat(properties.tokensForExtraSeat(1)).isZero();
    }

    @Test
    void negativeValuesNeverInventTokens() {
        SeatProperties properties = new SeatProperties(-3, -1_000L, null, null, "  ", null);

        assertThat(properties.includedSeats()).isEqualTo(1);
        assertThat(properties.tokensPerExtraSeat()).isZero();
        assertThat(properties.isBilled()).isFalse();
    }

    @Test
    void aFlatGrantAppliesToEveryExtraSeat() {
        SeatProperties properties = properties(4_000L, List.of());

        assertThat(properties.tokensForExtraSeat(1)).isEqualTo(4_000L);
        assertThat(properties.tokensForExtraSeat(9)).isEqualTo(4_000L);
        assertThat(properties.tokensForExtraSeat(0)).isZero();
    }

    @Test
    void tiersApplyByRankAndTheLastOneKeepsApplyingBeyond() {
        SeatProperties properties = properties(4_000L, List.of(
                new QuotaTier(2, 4_000L),
                new QuotaTier(5, 3_000L)));

        assertThat(properties.tokensForExtraSeat(1)).isEqualTo(4_000L);
        assertThat(properties.tokensForExtraSeat(2)).isEqualTo(4_000L);
        assertThat(properties.tokensForExtraSeat(3)).isEqualTo(3_000L);
        assertThat(properties.tokensForExtraSeat(5)).isEqualTo(3_000L);
        // Au-delà du dernier palier : une grille de remises finit toujours sur un « et au-delà ».
        assertThat(properties.tokensForExtraSeat(12)).isEqualTo(3_000L);
    }

    @Test
    void tiersAreSortedAndAberrantOnesAreDropped() {
        SeatProperties properties = properties(0L, Arrays.asList(
                new QuotaTier(5, 3_000L),
                new QuotaTier(0, 9_999L),
                new QuotaTier(2, 4_000L),
                new QuotaTier(3, null),
                null));

        assertThat(properties.quotaTiers()).extracting(QuotaTier::upToSeats).containsExactly(2, 5);
        assertThat(properties.tokensForExtraSeat(1)).isEqualTo(4_000L);
    }

    @Test
    void aPriceMakesTheSupplementBilled() {
        SeatProperties properties = new SeatProperties(
                1, 0L, List.of(), SeatProration.NONE, "price_extra_seat", "70");

        assertThat(properties.isBilled()).isTrue();
        assertThat(properties.displayPrice()).isEqualTo("70");
        assertThat(properties.proration()).isEqualTo(SeatProration.NONE);
    }

    private static SeatProperties properties(long flatTokens, List<QuotaTier> tiers) {
        return new SeatProperties(1, flatTokens, tiers, SeatProration.DAILY, null, null);
    }
}
