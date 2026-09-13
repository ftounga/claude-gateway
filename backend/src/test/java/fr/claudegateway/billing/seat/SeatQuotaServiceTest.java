package fr.claudegateway.billing.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.billing.seat.SeatProperties.QuotaTier;
import fr.claudegateway.billing.seat.SeatSource.BillableSeat;

/**
 * Le décompte des <b>mois-postes</b> et la part de quota qu'ils apportent (F-65 / SF-65-01).
 *
 * <p>Aucun montant n'est vérifié ici — il n'y en a aucun dans le code. Ce qui se vérifie, ce sont
 * les quatre règles du cadrage : l'abonnement couvre un poste, un poste ouvert en cours de mois est
 * proraté, un poste clôturé reste compté pour le mois engagé, et rouvrir n'apporte rien de plus.</p>
 */
class SeatQuotaServiceTest {

    /** 16 mars : un mois de 31 jours, et une date qui n'est ni le premier ni le dernier. */
    private static final Clock CLOCK =
            Clock.fixed(Instant.parse("2026-03-16T10:15:00Z"), ZoneOffset.UTC);
    private static final LocalDate PERIOD = LocalDate.of(2026, 3, 1);
    private static final long TOKENS = 4_200_000L;

    private final SeatSource seatSource = mock(SeatSource.class);
    private final HostSeatMonthRepository repository = mock(HostSeatMonthRepository.class);

    private final UUID alice = UUID.randomUUID();

    @Test
    void theSubscriptionCoversOneSeatSoASingleSeatGrantsNothing() {
        givenBillableSeats(seat("Poste CAGIP", PERIOD.minusMonths(4)));
        givenNoSeatMonths();

        SeatUsage usage = service(flat(TOKENS)).describe(alice);

        assertThat(usage.countedSeats()).isEqualTo(1);
        assertThat(usage.extraSeats()).isZero();
        assertThat(usage.grantedTokens()).isZero();
        assertThat(usage.seats()).singleElement()
                .satisfies(seat -> assertThat(seat.coveredByPlan()).isTrue());
    }

    @Test
    void everySeatBeyondTheFirstGrantsItsShare() {
        givenBillableSeats(
                seat("Poste A", PERIOD.minusMonths(4)),
                seat("Poste B", PERIOD.minusMonths(3)),
                seat("Poste C", PERIOD.minusMonths(2)));
        givenNoSeatMonths();

        SeatUsage usage = service(flat(TOKENS)).describe(alice);

        assertThat(usage.countedSeats()).isEqualTo(3);
        assertThat(usage.extraSeats()).isEqualTo(2);
        assertThat(usage.grantedTokens()).isEqualTo(2 * TOKENS);
        // Le poste couvert par l'abonnement est le PLUS ANCIEN : les suppléments sont les récents.
        assertThat(usage.seats().get(0).name()).isEqualTo("Poste A");
        assertThat(usage.seats().get(0).coveredByPlan()).isTrue();
        assertThat(usage.seats().get(1).extraSeatRank()).isEqualTo(1);
        assertThat(usage.seats().get(2).extraSeatRank()).isEqualTo(2);
    }

    @Test
    void aSeatOpenedMidMonthGrantsOnlyWhatIsLeftOfTheMonth() {
        givenBillableSeats(
                seat("Poste A", PERIOD.minusMonths(4)),
                seat("Poste B", LocalDate.of(2026, 3, 16)));
        givenNoSeatMonths();

        SeatUsage usage = service(flat(TOKENS)).describe(alice);

        // Du 16 au 31 inclus : 16 jours sur 31, tronqués vers le bas.
        assertThat(usage.grantedTokens()).isEqualTo(TOKENS * 16 / 31);
        assertThat(usage.seats().get(1).billableFrom()).isEqualTo(LocalDate.of(2026, 3, 16));
    }

    @Test
    void withoutProrationAMidMonthSeatGrantsTheWholeShare() {
        givenBillableSeats(
                seat("Poste A", PERIOD.minusMonths(4)),
                seat("Poste B", LocalDate.of(2026, 3, 16)));
        givenNoSeatMonths();

        SeatProperties noProration = new SeatProperties(
                1, TOKENS, List.of(), SeatProration.NONE, "price_extra_seat", null);

        assertThat(service(noProration).describe(alice).grantedTokens()).isEqualTo(TOKENS);
    }

    @Test
    void tiersMakeTheGrantDegressive() {
        givenBillableSeats(
                seat("Poste A", PERIOD.minusMonths(4)),
                seat("Poste B", PERIOD.minusMonths(4)),
                seat("Poste C", PERIOD.minusMonths(4)),
                seat("Poste D", PERIOD.minusMonths(4)));
        givenNoSeatMonths();

        SeatProperties tiered = new SeatProperties(1, 0L,
                List.of(new QuotaTier(2, 4_000L), new QuotaTier(9, 3_000L)),
                SeatProration.DAILY, "price_extra_seat", null);

        assertThat(service(tiered).describe(alice).grantedTokens())
                .isEqualTo(4_000L + 4_000L + 3_000L);
    }

    @Test
    void aSeatClosedMidMonthStaysCountedUntilTheEndOfTheEngagedMonth() {
        // Plus aucun poste facturable : le seul poste a été clôturé le 12.
        givenBillableSeats();
        givenSeatMonths(seatMonth(UUID.randomUUID(), LocalDate.of(2026, 3, 1)));
        when(seatSource.seatName(eq(alice), any())).thenReturn("Poste clôturé");

        SeatUsage usage = service(flat(TOKENS)).describe(alice);

        assertThat(usage.countedSeats()).isEqualTo(1);
        assertThat(usage.seats()).singleElement().satisfies(seat -> {
            assertThat(seat.closed()).isTrue();
            assertThat(seat.coveredByPlan()).isTrue();
        });
    }

    @Test
    void aReopenedSeatKeepsTheDateOfItsExistingSeatMonth() {
        UUID reopened = UUID.randomUUID();
        givenBillableSeats(
                seat("Poste A", PERIOD.minusMonths(4)),
                new BillableSeat(reopened, "Poste B", atStartOfDay(PERIOD.minusMonths(2))));
        // La ligne du mois dit « facturable depuis le 1er » : la réouverture du 16 ne la réécrit pas,
        // et le poste n'est donc pas proratisé une seconde fois.
        givenSeatMonths(seatMonth(reopened, LocalDate.of(2026, 3, 1)));

        SeatUsage usage = service(flat(TOKENS)).describe(alice);

        assertThat(usage.countedSeats()).isEqualTo(2);
        assertThat(usage.grantedTokens()).isEqualTo(TOKENS);
        assertThat(usage.seats().get(1).billableFrom()).isEqualTo(LocalDate.of(2026, 3, 1));
    }

    @Test
    void anUnconfiguredMechanismGrantsNothingAndReadsNothing() {
        SeatQuotaService service = service(new SeatProperties(null, null, null, null, null, null));

        assertThat(service.grantedTokens(alice)).isZero();
        // Le chemin chaud du pré-vol de quota ne doit rien coûter tant que rien n'est configuré.
        verifyNoInteractions(seatSource, repository);
    }

    @Test
    void theScreenKnowsWhetherTheSupplementIsActuallyBilled() {
        givenBillableSeats(seat("Poste A", PERIOD.minusMonths(4)));
        givenNoSeatMonths();

        SeatProperties billed = new SeatProperties(
                1, TOKENS, List.of(), SeatProration.DAILY, "price_extra_seat", "70");
        SeatUsage usage = service(billed).describe(alice);

        assertThat(usage.billed()).isTrue();
        assertThat(usage.displayPrice()).isEqualTo("70");
        assertThat(usage.periodStart()).isEqualTo(PERIOD);
        assertThat(usage.periodEnd()).isEqualTo(PERIOD.plusMonths(1));
    }

    // ------------------------------------------------ F-107 / SF-107-05 : par espace

    @Test
    void withoutABranchedPriceTheGridIsShownButNoTokenIsGranted() {
        givenBillableSeats(seat("Poste A", PERIOD.minusMonths(4)), seat("Poste B", PERIOD.minusMonths(4)));
        givenNoSeatMonths();
        SeatProperties grid = new SeatProperties(1, 0L, List.of(new QuotaTier(2, 2_000_000L, "39"),
                new QuotaTier(5, 1_500_000L, "29"), new QuotaTier(6, 1_000_000L, "19")), SeatProration.NONE, "", "39");

        SeatQuotaService service = service(grid);
        SeatUsage usage = service.describe(alice);

        assertThat(usage.billed()).isFalse();
        assertThat(usage.grantedTokens()).isZero();
        assertThat(usage.seats().get(1).displayPrice()).isEqualTo("39");
        assertThat(service.grantedTokens(alice)).isZero();
    }

    @Test
    void theForgeGridIsDegressiveInPriceAndTokens() {
        List<BillableSeat> eight = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            eight.add(seat("Poste " + i, PERIOD.minusMonths(4).plusDays(i)));
        }
        when(seatSource.billableSeats(alice)).thenReturn(eight);
        givenNoSeatMonths();
        SeatProperties grid = new SeatProperties(1, 0L, List.of(new QuotaTier(2, 2_000_000L, "39"),
                new QuotaTier(5, 1_500_000L, "29"), new QuotaTier(6, 1_000_000L, "19")), SeatProration.NONE,
                "price_forge", "39");

        SeatUsage usage = service(grid).describe(alice);

        assertThat(usage.seats()).extracting(SeatUsage.Seat::displayPrice)
                .containsExactly("", "39", "39", "29", "29", "29", "19", "19");
        assertThat(usage.grantedTokens()).isEqualTo(2 * 2_000_000L + 3 * 1_500_000L + 2 * 1_000_000L);
    }

    @Test
    void byokSeesTheAmountsButReceivesNoToken() {
        givenBillableSeats(seat("Poste A", PERIOD.minusMonths(4)), seat("Poste B", PERIOD.minusMonths(4)));
        givenNoSeatMonths();

        SeatUsage usage = service(flat(TOKENS)).describe(alice, fr.claudegateway.billing.EntitlementSpace.FORGE, false);

        assertThat(usage.tokensApply()).isFalse();
        assertThat(usage.grantedTokens()).isZero();
    }

    @Test
    void theVigieCountsItsOwnClientsAtItsFixedPriceWithoutTokens() {
        UUID vigieHost = UUID.randomUUID();
        when(seatSource.billableSeats(alice, fr.claudegateway.billing.EntitlementSpace.VIGIE)).thenReturn(List.of(
                seat("Suivi 1", PERIOD.minusMonths(2)),
                new BillableSeat(vigieHost, "Suivi 2", atStartOfDay(PERIOD.plusDays(3)))));
        // Une ligne FORGE du même mois ne doit rien compter dans la Vigie.
        HostSeatMonth forgeLine = seatMonth(UUID.randomUUID(), PERIOD);
        givenSeatMonths(forgeLine);
        SeatProperties properties = new SeatProperties(1, TOKENS, List.of(), SeatProration.DAILY, "price_forge", "39",
                new SeatProperties.Vigie(1, "", "39"));

        SeatUsage usage = service(properties).describe(alice, fr.claudegateway.billing.EntitlementSpace.VIGIE, true);

        assertThat(usage.space()).isEqualTo(fr.claudegateway.billing.EntitlementSpace.VIGIE);
        assertThat(usage.countedSeats()).isEqualTo(2);
        assertThat(usage.extraSeats()).isEqualTo(1);
        assertThat(usage.grantedTokens()).isZero();
        assertThat(usage.tokensApply()).isFalse();
        assertThat(usage.billed()).isFalse();
        assertThat(usage.displayPrice()).isEqualTo("39");
        assertThat(usage.seats().get(1).billableFrom()).isEqualTo(PERIOD.plusDays(3));
        assertThat(usage.seats().get(1).displayPrice()).isEqualTo("39");
    }

    private SeatQuotaService service(SeatProperties properties) {
        return new SeatQuotaService(seatSource, repository, properties, CLOCK);
    }

    private static SeatProperties flat(long tokens) {
        return new SeatProperties(1, tokens, List.of(), SeatProration.DAILY, "price_extra_seat", null);
    }

    private void givenBillableSeats(BillableSeat... seats) {
        when(seatSource.billableSeats(alice)).thenReturn(List.of(seats));
    }

    private void givenNoSeatMonths() {
        when(repository.findByUserIdAndPeriodStart(alice, PERIOD)).thenReturn(List.of());
    }

    private void givenSeatMonths(HostSeatMonth... months) {
        when(repository.findByUserIdAndPeriodStart(alice, PERIOD))
                .thenReturn(new ArrayList<>(List.of(months)));
    }

    private static BillableSeat seat(String name, LocalDate createdOn) {
        return new BillableSeat(UUID.randomUUID(), name, atStartOfDay(createdOn));
    }

    private HostSeatMonth seatMonth(UUID hostId, LocalDate billableFrom) {
        return HostSeatMonth.builder()
                .id(UUID.randomUUID())
                .userId(alice)
                .hostId(hostId)
                .periodStart(PERIOD)
                .billableFrom(billableFrom)
                .build();
    }

    private static OffsetDateTime atStartOfDay(LocalDate date) {
        return date.atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
