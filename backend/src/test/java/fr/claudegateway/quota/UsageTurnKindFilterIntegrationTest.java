package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-224 / SF-224-06 — les lectures qui parlent de TOURS ne comptent que les tours ({@code kind = 'TOUR'}) : les appels
 * annexes (aide, index de la carte), inscrits au journal du coût depuis SF-224-03, ne faussent ni la moyenne d'un tour,
 * ni la consommation par client lue à côté du quota, ni le diagnostic. Le coût par client, lui, les garde (« hors
 * client ») : c'est de la dépense réelle. Isolation : toutes ces lectures filtrent {@code user_id}.
 */
@SpringBootTest
@ActiveProfiles("test")
class UsageTurnKindFilterIntegrationTest {

    @Autowired private UsageTurnRepository turns;
    @Autowired private UserRepository users;

    private UUID userId;
    private UUID otherId;
    private final OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        userId = user("kind-a-");
        otherId = user("kind-b-");
        save(userId, UsageTurn.Kind.TOUR, "2.00", 1_000L);
        save(userId, UsageTurn.Kind.TOUR, "4.00", 1_000L);
        save(userId, UsageTurn.Kind.ANNEXE, "0.01", 500L);
        save(otherId, UsageTurn.Kind.TOUR, "100.00", 9_000L);
    }

    @Test
    void theAverageTurnCostIgnoresAnnexCallsAndOtherAccounts() {
        assertThat(turns.averageCostSince(userId, now.minusDays(1))).isEqualTo(3.0);
    }

    @Test
    void theUsageByClientCountsTurnsOnly() {
        List<UsageTurnAggregate> rows = turns.aggregateByHostAndWorkspace(userId, now.minusDays(1), now.plusDays(1));
        long input = rows.stream().mapToLong(UsageTurnAggregate::getInputTokens).sum();
        assertThat(input).isEqualTo(2_000L);
    }

    @Test
    void theDiagnosticReadsTurnsOnly() {
        assertThat(turns.findByUserIdAndOccurredAtBetweenOrderByOccurredAtAsc(userId, now.minusDays(1),
                now.plusDays(1))).hasSize(2).allMatch(t -> t.getKind() == UsageTurn.Kind.TOUR);
    }

    @Test
    void theSpendByClientKeepsAnnexCalls() {
        BigDecimal spent = turns.aggregateCostByHost(userId, now.minusDays(1), now.plusDays(1)).stream()
                .map(HostCostAggregate::getCostUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(spent).isEqualByComparingTo("6.01");
    }

    private UUID user(String prefix) {
        return users.save(User.builder().email(prefix + UUID.randomUUID() + "@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build()).getId();
    }

    private void save(UUID owner, UsageTurn.Kind kind, String cost, long input) {
        turns.save(UsageTurn.builder().userId(owner).inputTokens(input).outputTokens(10L)
                .providerCostUsd(new BigDecimal(cost)).costSource(TurnCost.Source.CALCULATED)
                .model("claude-opus-5-5").kind(kind).occurredAt(now).build());
    }
}
