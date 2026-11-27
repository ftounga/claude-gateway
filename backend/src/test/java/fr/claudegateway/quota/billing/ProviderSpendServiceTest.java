package fr.claudegateway.quota.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.ai.bedrock.BedrockProperties;
import fr.claudegateway.ai.failover.AiChannel;
import fr.claudegateway.ai.failover.ProviderFailover;
import fr.claudegateway.quota.ProviderPricingProperties;
import fr.claudegateway.quota.UsageTurn;
import fr.claudegateway.quota.UsageTurnRepository;

/**
 * F-279 / SF-279-01 — les deux jauges du mois : jour clos et facturé ⇒ facture, sinon ⇒ calcul ; canal en service.
 * Horloge fixe (27/11/2026 16:00 UTC) : jours clos = jusqu'au 25/11.
 */
class ProviderSpendServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 27, 16, 0, 0, 0, ZoneOffset.UTC);
    private static final Clock CLOCK = Clock.fixed(Instant.from(NOW), ZoneOffset.UTC);

    private final ProviderBillingReport anthropicBilling = mock(ProviderBillingReport.class);
    private final BedrockBillingReport bedrockBilling = mock(BedrockBillingReport.class);
    private final ProviderCostReportRepository reports = mock(ProviderCostReportRepository.class);
    private final UsageTurnRepository usageTurns = mock(UsageTurnRepository.class);
    private final ProviderCostReconciliationService reconciliation = mock(ProviderCostReconciliationService.class);
    private final ProviderFailover failover = mock(ProviderFailover.class);
    private final AdminService adminService = mock(AdminService.class);
    private final ProviderPricingProperties pricing = new ProviderPricingProperties(null, null, null, null, null,
            new BigDecimal("0.92"));
    private final List<Object[]> turns = new ArrayList<>();
    private final List<ProviderCostReport> rows = new ArrayList<>();

    private ProviderSpendService service;

    @BeforeEach
    void setUp() {
        service = new ProviderSpendService(anthropicBilling, bedrockBilling, reports, usageTurns, reconciliation,
                failover, adminService, pricing, CLOCK);
        when(usageTurns.claudeCostsBetween(any(), any())).thenReturn(turns);
        when(reports.findByDayGreaterThanEqualAndDayLessThanOrderByDayAsc(any(), any())).thenReturn(rows);
        when(reports.findFirstByKindInOrderByFetchedAtDesc(any())).thenReturn(Optional.empty());
        when(reports.findByDayAndKindAndApiKeyId(any(), anyString(), anyString())).thenReturn(Optional.empty());
        when(failover.mode()).thenReturn(BedrockProperties.Mode.AUTO);
        when(failover.currentChannel()).thenReturn(AiChannel.ANTHROPIC);
        when(failover.lastCause()).thenReturn(Optional.empty());
        when(failover.openDuration()).thenReturn(java.time.Duration.ofMinutes(15));
        when(usageTurns.findFirstByOccurredAtGreaterThanEqualAndModelContainingIgnoreCaseOrderByOccurredAtDesc(any(),
                anyString())).thenReturn(Optional.empty());
        when(bedrockBilling.enabled()).thenReturn(true);
        when(anthropicBilling.configured()).thenReturn(true);
    }

    private void turn(int day, String usd, AiChannel channel) {
        turns.add(new Object[] {OffsetDateTime.of(2026, 11, day, 12, 0, 0, 0, ZoneOffset.UTC), new BigDecimal(usd),
                channel});
    }

    private void billed(int day, String kind, String usd) {
        rows.add(ProviderCostReport.builder().day(LocalDate.of(2026, 11, day)).kind(kind).apiKeyId("")
                .amountUsd(new BigDecimal(usd)).build());
    }

    @Test
    @DisplayName("jour clos et facturé ⇒ facture ; jours récents ⇒ calcul ; un canal ne compte pas l'autre")
    void recalibratesSettledDaysAndEstimatesTheRest() {
        turn(25, "10.00", AiChannel.ANTHROPIC);
        turn(26, "17.04", AiChannel.BEDROCK);
        turn(27, "30.00", AiChannel.BEDROCK);
        turn(27, "5.00", AiChannel.ANTHROPIC);
        billed(25, ProviderCostReport.COST, "11.00");
        billed(26, ProviderCostReport.BEDROCK, "17.27"); // 26 = hier : pas clos, ignoré
        billed(24, ProviderCostReport.BEDROCK, "2.00"); // clos, facturé, rien calculé
        billed(25, ProviderCostReport.KEY, "999"); // ligne par clé : jamais comptée

        ProviderSpendService.ProviderSpend spend = service.view();

        verify(adminService).assertAdmin();
        assertThat(spend.month()).isEqualTo("2026-11");
        ProviderSpendService.Gauge anthropic = spend.gauges().get(0);
        assertThat(anthropic.provider()).isEqualTo(AiChannel.ANTHROPIC);
        assertThat(anthropic.billing()).isEqualTo(ProviderSpendService.Billing.PREPAID);
        assertThat(anthropic.billedEur()).isEqualByComparingTo("10.12"); // 11 × 0,92
        assertThat(anthropic.estimatedEur()).isEqualByComparingTo("4.60"); // 5 × 0,92
        assertThat(anthropic.spentEur()).isEqualByComparingTo("14.72");
        assertThat(anthropic.calculatedEur()).isEqualByComparingTo("13.80");
        assertThat(anthropic.gapEur()).isEqualByComparingTo("0.92");
        assertThat(anthropic.billedThrough()).isEqualTo(LocalDate.of(2026, 11, 25));
        assertThat(anthropic.status()).isEqualTo(ProviderSpendService.Status.RECALE);
        assertThat(anthropic.statusNote()).contains("jusqu'au 25/11");

        ProviderSpendService.Gauge bedrock = spend.gauges().get(1);
        assertThat(bedrock.billing()).isEqualTo(ProviderSpendService.Billing.POSTPAID);
        assertThat(bedrock.billedEur()).isEqualByComparingTo("1.84");
        assertThat(bedrock.estimatedEur()).isEqualByComparingTo("43.28"); // (17,04 + 30) × 0,92
        assertThat(bedrock.spentEur()).isEqualByComparingTo("45.12");
        assertThat(bedrock.billedThrough()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(spend.totalEur()).isEqualByComparingTo("59.84");
        assertThat(spend.caveats()).anyMatch(c -> c.contains("Hors TVA")).anyMatch(c -> c.contains("Prépayé"));
        assertThat(spend.settleDays()).isEqualTo(2);
    }

    @Test
    @DisplayName("sans facture : tout estimé, et la note dit pourquoi (clé Admin absente, droit AWS manquant)")
    void withoutBillsEverythingIsEstimatedAndExplained() {
        turn(27, "1.00", AiChannel.BEDROCK);
        when(anthropicBilling.configured()).thenReturn(false);
        when(bedrockBilling.billedCosts(any(), any()))
                .thenThrow(new BedrockBillingReport.Unavailable(BedrockBillingReport.Reason.DENIED));

        assertThat(service.refreshBedrock()).isZero();
        verify(reports, never()).save(any());
        ProviderSpendService.ProviderSpend spend = service.view();

        assertThat(spend.gauges().get(0).status()).isEqualTo(ProviderSpendService.Status.ESTIME);
        assertThat(spend.gauges().get(0).statusNote()).contains("APP_ANTHROPIC_ADMIN_KEY");
        assertThat(spend.gauges().get(0).gapEur()).isNull();
        assertThat(spend.gauges().get(1).status()).isEqualTo(ProviderSpendService.Status.ESTIME);
        assertThat(spend.gauges().get(1).statusNote()).contains("ce:GetCostAndUsage");
        assertThat(spend.gauges().get(1).spentEur()).isEqualByComparingTo("0.92");
    }

    @Test
    @DisplayName("relecture AWS : chaque jour rangé en BEDROCK ; un succès efface l'échec retenu")
    void refreshStoresBedrockDays() {
        when(bedrockBilling.billedCosts(any(), any())).thenReturn(List.of(
                new BedrockBillingReport.BilledDay(LocalDate.of(2026, 11, 26), new BigDecimal("17.2717"))));
        assertThat(service.refreshBedrock()).isEqualTo(1);
        verify(reports).save(org.mockito.ArgumentMatchers.argThat(r -> ProviderCostReport.BEDROCK.equals(r.getKind())
                && "".equals(r.getApiKeyId()) && r.getAmountUsd().compareTo(new BigDecimal("17.2717")) == 0
                && r.getFetchedAt() != null));
        assertThat(service.view().gauges().get(1).statusNote()).doesNotContain("échec");
    }

    @Test
    @DisplayName("canal : disjoncteur ouvert pour crédit ⇒ Bedrock, crédit épuisé ; autre pod qui relaie ⇒ Bedrock")
    void tellsTheChannelInService() {
        when(failover.currentChannel()).thenReturn(AiChannel.BEDROCK);
        when(failover.circuitOpen()).thenReturn(true);
        when(failover.lastCause()).thenReturn(Optional.of(ProviderFailover.Cause.CREDIT));
        ProviderSpendService.Channel open = service.view().channel();
        assertThat(open.current()).isEqualTo(AiChannel.BEDROCK);
        assertThat(open.relay()).isTrue();
        assertThat(open.creditExhausted()).isTrue();
        assertThat(open.cause()).isEqualTo("CREDIT");

        // Ce pod est fermé, mais le dernier tour Claude (il y a 5 min) a été servi par Bedrock.
        when(failover.currentChannel()).thenReturn(AiChannel.ANTHROPIC);
        when(failover.circuitOpen()).thenReturn(false);
        when(failover.lastCause()).thenReturn(Optional.empty());
        UsageTurn last = UsageTurn.builder().provider(AiChannel.BEDROCK).occurredAt(NOW.minusMinutes(5)).build();
        when(usageTurns.findFirstByOccurredAtGreaterThanEqualAndModelContainingIgnoreCaseOrderByOccurredAtDesc(any(),
                anyString())).thenReturn(Optional.of(last));
        ProviderSpendService.Channel relayed = service.view().channel();
        assertThat(relayed.current()).isEqualTo(AiChannel.BEDROCK);
        assertThat(relayed.relay()).isTrue();
        assertThat(relayed.creditExhausted()).isFalse();
        assertThat(relayed.lastServed()).isEqualTo(AiChannel.BEDROCK);

        // Dernier tour Bedrock trop ancien : Anthropic a repris.
        last.setOccurredAt(NOW.minusHours(1));
        assertThat(service.view().channel().current()).isEqualTo(AiChannel.ANTHROPIC);
    }
}
