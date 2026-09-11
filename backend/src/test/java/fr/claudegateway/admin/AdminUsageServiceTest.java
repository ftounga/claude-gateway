package fr.claudegateway.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.quota.UsageCostEstimator;
import fr.claudegateway.quota.UsageCounter;
import fr.claudegateway.quota.UsageCounterRepository;
import fr.claudegateway.quota.UsageReportProperties;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests de la consommation par utilisateur côté console d'administration (F-61 / SF-61-03) :
 * agrégation, distinction entrée/sortie, parts, évolution, garde ADMIN et fenêtre.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminUsageServiceTest {

    @Mock
    private AdminService adminService;
    @Mock
    private UsageCounterRepository usageCounterRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private SubscriptionRepository subscriptionRepository;

    private AdminUsageService service;

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();

    private final Clock clock = Clock.fixed(Instant.parse("2026-09-11T08:00:00Z"), ZoneOffset.UTC);
    private final UsageReportProperties properties =
            new UsageReportProperties("EUR", 12, new BigDecimal("3.00"), new BigDecimal("15.00"));

    private final LocalDate august = LocalDate.of(2026, 8, 1);
    private final LocalDate september = LocalDate.of(2026, 9, 1);

    @BeforeEach
    void setUp() {
        service = new AdminUsageService(adminService, usageCounterRepository, userRepository,
                subscriptionRepository, new UsageCostEstimator(properties), clock);
        when(userRepository.findAllById(any())).thenReturn(List.of(
                user(alice, "alice@example.com"), user(bob, "bob@example.com")));
        when(subscriptionRepository.findByUserId(alice)).thenReturn(Optional.of(
                Subscription.builder().userId(alice).planCode(PlanCode.PRO)
                        .status(SubscriptionStatus.ACTIVE).build()));
        when(subscriptionRepository.findByUserId(bob)).thenReturn(Optional.empty());
    }

    private User user(UUID id, String email) {
        return User.builder().id(id).email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build();
    }

    private UsageCounter counter(UUID userId, LocalDate period, long input, long output) {
        return UsageCounter.builder().userId(userId).periodStart(period)
                .inputTokens(input).outputTokens(output).build();
    }

    private void stubCounters(UsageCounter... counters) {
        when(usageCounterRepository.findByPeriodStartGreaterThanEqualAndPeriodStartLessThan(
                any(), any())).thenReturn(List.of(counters));
    }

    @Test
    void aggregatesByUserSortedByConsumption() {
        stubCounters(
                counter(bob, september, 100_000L, 10_000L),
                counter(alice, september, 900_000L, 180_000L));

        AdminUsage usage = service.byUser(null, null);

        assertThat(usage.users()).hasSize(2);
        assertThat(usage.users().get(0).email()).isEqualTo("alice@example.com");
        assertThat(usage.users().get(1).email()).isEqualTo("bob@example.com");
    }

    @Test
    void inputAndOutputAreNeverMerged() {
        stubCounters(counter(alice, september, 1_000_000L, 1_000_000L));

        AdminUsage usage = service.byUser(null, null);

        AdminUsage.UserUsage row = usage.users().get(0);
        assertThat(row.inputTokens()).isEqualTo(1_000_000L);
        assertThat(row.outputTokens()).isEqualTo(1_000_000L);
        // 1 M × 3 € + 1 M × 15 € = 18 € — jamais 2 M × un tarif moyen.
        assertThat(row.estimatedCost()).isEqualByComparingTo("18.0000");
    }

    @Test
    void sharesSumToOne() {
        stubCounters(
                counter(alice, september, 750_000L, 0L),
                counter(bob, september, 250_000L, 0L));

        AdminUsage usage = service.byUser(null, null);

        assertThat(usage.users().get(0).share()).isEqualByComparingTo("0.7500");
        assertThat(usage.users().get(1).share()).isEqualByComparingTo("0.2500");
    }

    @Test
    void monthlyEvolutionIsOldestFirst() {
        stubCounters(
                counter(alice, september, 30_000L, 0L),
                counter(alice, august, 10_000L, 0L));

        AdminUsage usage = service.byUser(null, null);

        List<AdminUsage.MonthUsage> periods = usage.users().get(0).periods();
        assertThat(periods).hasSize(2);
        assertThat(periods.get(0).periodStart()).isEqualTo(august);
        assertThat(periods.get(1).periodStart()).isEqualTo(september);
        assertThat(usage.users().get(0).totalTokens()).isEqualTo(40_000L);
    }

    @Test
    void planIsAttachedWhenSubscribed() {
        stubCounters(counter(alice, september, 1_000L, 0L), counter(bob, september, 500L, 0L));

        AdminUsage usage = service.byUser(null, null);

        assertThat(usage.users().get(0).planCode()).isEqualTo("PRO");
        assertThat(usage.users().get(0).subscriptionStatus()).isEqualTo("ACTIVE");
        assertThat(usage.users().get(1).planCode()).isNull();
    }

    @Test
    void accountsWithoutConsumptionAreAbsent() {
        stubCounters(counter(alice, september, 1_000L, 0L));

        AdminUsage usage = service.byUser(null, null);

        assertThat(usage.users()).singleElement()
                .satisfies(row -> assertThat(row.email()).isEqualTo("alice@example.com"));
    }

    @Test
    void emptyPlatformIsNotAnError() {
        stubCounters();

        AdminUsage usage = service.byUser(null, null);

        assertThat(usage.users()).isEmpty();
        assertThat(usage.totalTokens()).isZero();
        assertThat(usage.estimatedCost()).isEqualByComparingTo("0");
    }

    @Test
    void nonAdminIsRefusedBeforeAnyRead() {
        doThrow(new AdminForbiddenException()).when(adminService).assertAdmin();

        assertThatThrownBy(() -> service.byUser(null, null))
                .isInstanceOf(AdminForbiddenException.class);
    }

    @Test
    void windowBoundsAreReportedBack() {
        stubCounters();

        AdminUsage usage = service.byUser(LocalDate.of(2026, 7, 19), LocalDate.of(2026, 9, 4));

        assertThat(usage.from()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(usage.to()).isEqualTo(september);
    }

    @Test
    void invertedWindowIsRefused() {
        assertThatThrownBy(() -> service.byUser(september, LocalDate.of(2026, 4, 1)))
                .isInstanceOf(fr.claudegateway.quota.InvalidUsageWindowException.class);
    }
}
