package fr.claudegateway.jarvis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import fr.claudegateway.quota.CostWindow;
import fr.claudegateway.quota.HostCostService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.quota.TurnExtras;
import fr.claudegateway.quota.TurnTokens;
import fr.claudegateway.quota.UsageByClient;
import fr.claudegateway.quota.UsageByClientService;
import fr.claudegateway.quota.UsageTag;
import fr.claudegateway.quota.UsageTurn;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-244 / SF-244-02 — <b>Jarvis ne consomme plus le quota Gateway</b> : un tour né de Jarvis est écrit au journal
 * (origine {@code JARVIS}) mais n'incrémente pas {@code usage_counters} ; la consommation par client (lue à côté du
 * quota) l'exclut et le montre dans une ligne « dont Jarvis », en euros ; la dépense par client (budgets F-133) le
 * garde, ventilé. Isolation A/B.
 */
@SpringBootTest
@ActiveProfiles("test")
class JarvisOutsideGatewayQuotaIntegrationTest {

    private static final UsageTag JARVIS_OPUS = UsageTag.jarvis(UsageTurn.JarvisPart.OPUS);

    @Autowired private UserRepository users;
    @Autowired private QuotaService quota;
    @Autowired private UsageByClientService byClient;
    @Autowired private HostCostService hostCosts;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        alice = users.save(user("jarvis-quota-a-"));
        bob = users.save(user("jarvis-quota-b-"));
    }

    private static User user(String prefix) {
        return User.builder().email(prefix + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build();
    }

    @Test
    @DisplayName("un tour Jarvis ne bouge pas usage_counters ; un tour tapé, si")
    void aJarvisTurnLeavesTheGatewayCounterUntouched() {
        long before = quota.currentUsage(alice.getId()).usedTokens();

        quota.recordUsage(alice.getId(), new TurnTokens(10_000L, 2_000L, 0L, 0L), TurnExtras.NONE, null,
                "claude-opus-5", null, null, null, null, JARVIS_OPUS);
        assertThat(quota.currentUsage(alice.getId()).usedTokens()).isEqualTo(before);

        quota.recordUsage(alice.getId(), new TurnTokens(1_000L, 100L, 0L, 0L), TurnExtras.NONE, null,
                "claude-opus-5", null, null);
        assertThat(quota.currentUsage(alice.getId()).usedTokens()).isGreaterThan(before);
    }

    @Test
    @DisplayName("par client : Jarvis hors des totaux, montré dans « dont Jarvis » en euros ; B n'en voit rien")
    void theUsageByClientShowsJarvisApart() {
        quota.recordUsage(alice.getId(), new TurnTokens(10_000L, 2_000L, 0L, 0L), TurnExtras.NONE, null,
                "claude-opus-5", null, null, null, null, JARVIS_OPUS);
        quota.recordUsage(alice.getId(), new TurnTokens(1_000L, 100L, 0L, 0L), TurnExtras.NONE, null,
                "claude-opus-5", null, null);

        UsageByClient usage = byClient.byClient(alice.getId(), null, null);
        assertThat(usage.totalTokens()).isEqualTo(1_100L);
        assertThat(usage.jarvis().totalTokens()).isEqualTo(12_000L);
        assertThat(usage.jarvis().costEur()).isGreaterThan(BigDecimal.ZERO);

        UsageByClient other = byClient.byClient(bob.getId(), null, null);
        assertThat(other.totalTokens()).isZero();
        assertThat(other.jarvis().totalTokens()).isZero();
    }

    @Test
    @DisplayName("dépense par client : le total reste vrai (Jarvis compris) et sa part est ventilée")
    void theSpendByClientKeepsJarvisAndBreaksItDown() {
        quota.recordUsage(alice.getId(), new TurnTokens(10_000L, 2_000L, 0L, 0L), TurnExtras.NONE, null,
                "claude-opus-5", null, null, null, null, JARVIS_OPUS);
        quota.recordUsage(alice.getId(), new TurnTokens(1_000L, 100L, 0L, 0L), TurnExtras.NONE, null,
                "claude-opus-5", null, null);

        CostWindow week = CostWindow.lastSevenDays(java.time.Clock.systemUTC());
        BigDecimal total = hostCosts.costs(alice.getId(), week).costUsd();
        BigDecimal jarvis = hostCosts.jarvisCostByHost(alice.getId(), week).values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(jarvis).isGreaterThan(BigDecimal.ZERO);
        assertThat(total).isGreaterThan(jarvis);
        assertThat(hostCosts.jarvisCostByHost(bob.getId(), week)).isEmpty();
    }
}
