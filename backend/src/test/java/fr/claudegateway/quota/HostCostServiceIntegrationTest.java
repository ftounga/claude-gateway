package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * La dépense réelle par client et par semaine (F-133 / SF-133-03).
 *
 * <p>Le test qui compte est {@link #oneAccountNeverSeesAnother()} : deux comptes, deux postes
 * portant le <b>même nom</b>. C'est la forme sous laquelle une fuite d'isolation passerait
 * inaperçue.</p>
 */
@SpringBootTest
@ActiveProfiles("test")
class HostCostServiceIntegrationTest {

    @Autowired
    private HostCostService service;
    @Autowired
    private UsageTurnRepository turns;
    @Autowired
    private RunnerHostRepository hosts;
    @Autowired
    private UserRepository users;

    private UUID aliceId;
    private UUID bobId;
    private UUID aliceHost;

    /** Lundi de la semaine observée. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 9, 14);

    @BeforeEach
    void setUp() {
        turns.deleteAll();
        hosts.deleteAll();
        aliceId = newUser("alice-cout");
        bobId = newUser("bob-cout");
        aliceHost = hosts.save(host(aliceId, "poste-cagip")).getId();
    }

    @Test
    void sumsTheRealCostOfTheWeekAndExcludesTheNeighbouringOne() {
        turn(aliceId, aliceHost, MONDAY.plusDays(1), "0.250000");
        turn(aliceId, aliceHost, MONDAY.plusDays(4), "0.750000");
        // Le lundi suivant appartient à la semaine d'après : il ne doit pas entrer dans le total.
        turn(aliceId, aliceHost, MONDAY.plusDays(7), "9.000000");

        HostCost cost = service.costs(aliceId, CostWindow.week(MONDAY));

        assertThat(cost.costUsd()).isEqualByComparingTo("1.000000");
        assertThat(cost.clients()).hasSize(1);
        assertThat(cost.clients().get(0).hostName()).isEqualTo("poste-cagip");
    }

    @Test
    void aWeekStraddlingTwoMonthsKeepsBothSides() {
        // Lundi 28 septembre → dimanche 4 octobre. Un budget hebdomadaire ne s'arrête pas au mois.
        turn(aliceId, aliceHost, LocalDate.of(2026, 9, 30), "1.000000");
        turn(aliceId, aliceHost, LocalDate.of(2026, 10, 2), "2.000000");

        assertThat(service.costs(aliceId, CostWindow.week(LocalDate.of(2026, 10, 1))).costUsd())
                .isEqualByComparingTo("3.000000");
    }

    @Test
    void turnsWithoutACostCountAsZeroAndNotAsAnEstimate() {
        // Les tours d'avant F-133 n'ont pas de coût. Leur substituer une estimation donnerait un
        // montant crédible et faux : un trou se voit, une approximation non.
        turn(aliceId, aliceHost, MONDAY.plusDays(1), null);
        turn(aliceId, aliceHost, MONDAY.plusDays(2), "0.500000");

        HostCost cost = service.costs(aliceId, CostWindow.week(MONDAY));

        assertThat(cost.costUsd()).isEqualByComparingTo("0.500000");
        // Les volumes, eux, comptent les deux tours.
        assertThat(cost.clients().get(0).totalTokens()).isEqualTo(2_200L);
    }

    @Test
    void turnsWithoutAHostLandInTheOutsideBucketAndComeLast() {
        turn(aliceId, aliceHost, MONDAY.plusDays(1), "0.100000");
        turn(aliceId, null, MONDAY.plusDays(1), "5.000000");

        HostCost cost = service.costs(aliceId, CostWindow.week(MONDAY));

        assertThat(cost.costUsd()).isEqualByComparingTo("5.100000");
        assertThat(cost.clients()).hasSize(2);
        // « Hors client » vient en dernier malgré son montant : il n'est pas un client.
        assertThat(cost.clients().get(1).hostId()).isNull();
        assertThat(cost.clients().get(1).costUsd()).isEqualByComparingTo("5.000000");
    }

    @Test
    void oneAccountNeverSeesAnother() {
        UUID bobHost = hosts.save(host(bobId, "poste-cagip")).getId();
        turn(aliceId, aliceHost, MONDAY.plusDays(1), "1.000000");
        turn(bobId, bobHost, MONDAY.plusDays(1), "999.000000");

        HostCost cost = service.costs(aliceId, CostWindow.week(MONDAY));

        assertThat(cost.costUsd()).isEqualByComparingTo("1.000000");
        assertThat(cost.clients()).hasSize(1);
        assertThat(cost.clients().get(0).hostId()).isEqualTo(aliceHost);
    }

    @Test
    void theCostOfOneHostIsZeroWhenItSpentNothing() {
        assertThat(service.costOfHost(aliceId, aliceHost, CostWindow.week(MONDAY)))
                .isEqualByComparingTo("0");
    }

    // ------------------------------------------------------------------ montage

    private UUID newUser(String prefix) {
        return users.save(User.builder()
                .email(prefix + "-" + UUID.randomUUID() + "@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build())
                .getId();
    }

    private RunnerHost host(UUID userId, String name) {
        return RunnerHost.builder().userId(userId).name(name)
                .createdAt(OffsetDateTime.now()).updatedAt(OffsetDateTime.now()).build();
    }

    private void turn(UUID userId, UUID hostId, LocalDate day, String costUsd) {
        turns.save(UsageTurn.builder()
                .userId(userId).hostId(hostId)
                .inputTokens(1_000L).outputTokens(100L)
                .providerCostUsd(costUsd == null ? null : new BigDecimal(costUsd))
                .occurredAt(day.atTime(12, 0).atOffset(ZoneOffset.UTC))
                .build());
    }
}
