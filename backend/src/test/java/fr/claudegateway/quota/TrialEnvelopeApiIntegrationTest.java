package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ProviderFileReference;
import fr.claudegateway.ai.ProviderFileUpload;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration de l'enveloppe d'essai (F-66 / SF-66-01) sur {@code /api/**}.
 *
 * <p>Le scénario est celui qui faisait fuir le plafond : un essai commencé <b>le mois dernier</b> et
 * qui a déjà consommé son enveloppe. Avant F-66, le 1er du mois lui rendait un plafond entier ;
 * désormais il reste bloqué, et la jauge le dit.</p>
 *
 * <p>Quota d'essai abaissé à 30 jetons pour rester lisible, comme dans {@code UsageApiIntegrationTest}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "app.quota.trial-tokens=30")
class TrialEnvelopeApiIntegrationTest {

    /** Stub renvoyant 16 + 4 = 20 tokens traités, soit 20 tokens facturés au ratio d'équilibre. */
    static class StubAIProvider implements AIProvider {
        @Override
        public ChatCompletionResult complete(ChatCompletionRequest request) {
            return new ChatCompletionResult("Réponse", request.model(), 16, 4);
        }

        @Override
        public ProviderFileReference uploadFile(ProviderFileUpload upload) {
            return new ProviderFileReference("file_stub");
        }
    }

    @TestConfiguration
    static class StubProviderConfig {
        @Bean
        @Primary
        StubAIProvider stubAIProvider() {
            return new StubAIProvider();
        }
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private UsageCounterRepository usageCounterRepository;
    @Autowired
    private SubscriptionRepository subscriptionRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User alice;
    private String aliceToken;
    private User bob;
    private String bobToken;

    private final LocalDate currentPeriod =
            LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
    private final LocalDate previousPeriod = currentPeriod.minusMonths(1);

    @BeforeEach
    void setUp() {
        usageCounterRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        alice = userRepository.save(User.builder()
                .email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);

        bob = userRepository.save(User.builder()
                .email("bob@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        bobToken = jwtService.generateToken(bob);
    }

    /**
     * Provisionne l'essai de cet utilisateur (premier accès), puis le fait commencer le mois dernier.
     *
     * <p>{@code created_at} porte {@code @CreationTimestamp} et n'est pas modifiable par JPA : on
     * l'antidate en SQL, ce qui est précisément ce que fera le temps qui passe en production.</p>
     */
    private void startTrialLastMonth(String token, UUID userId) throws Exception {
        // C'est la consultation de l'abonnement qui provisionne l'essai : `GET /usage` le résout dans
        // une transaction en lecture seule, où l'insertion ne serait jamais écrite.
        mockMvc.perform(get("/api/billing/subscription").contextPath("/api")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        Subscription subscription = subscriptionRepository.findByUserId(userId).orElseThrow();
        // Six jours avant le 1er : toujours dans le mois précédent, quelle que soit la date du jour.
        // L'essai court encore (il se termine dans huit jours) : c'est exactement le cas qui fuyait.
        subscription.setTrialEndsAt(OffsetDateTime.now(ZoneOffset.UTC).plusDays(8));
        subscriptionRepository.save(subscription);
        jdbcTemplate.update("update subscriptions set created_at = ? where id = ?",
                trialStart(), subscription.getId());
    }

    /** Début de l'essai : six jours avant le 1er du mois courant, donc dans le mois précédent. */
    private OffsetDateTime trialStart() {
        return currentPeriod.minusDays(6).atStartOfDay().atOffset(ZoneOffset.UTC);
    }

    /** Consommation déjà enregistrée sur le mois précédent (celui où l'essai a commencé). */
    private void usedLastMonth(UUID userId, long billedTokens) {
        usageCounterRepository.save(UsageCounter.builder()
                .userId(userId).periodStart(previousPeriod)
                .inputTokens(billedTokens).outputTokens(0L)
                .billedTokens(billedTokens).build());
    }

    @Test
    void trialExhaustedLastMonthIsStillBlockedThisMonth() throws Exception {
        startTrialLastMonth(aliceToken, alice.getId());
        usedLastMonth(alice.getId(), 30L);

        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error", is("quota_exceeded")));
    }

    @Test
    void gaugeCountsTheWholeTrialAndNotTheMonth() throws Exception {
        startTrialLastMonth(aliceToken, alice.getId());
        usedLastMonth(alice.getId(), 20L);

        mockMvc.perform(get("/api/usage").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usedTokens", is(20)))
                .andExpect(jsonPath("$.quotaTokens", is(30)))
                .andExpect(jsonPath("$.remainingTokens", is(10)))
                .andExpect(jsonPath("$.periodStart", is(currentPeriod.minusDays(6).toString())));

        // Il reste 10 jetons : un appel passe (et en consomme 20), le suivant est refusé.
        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Encore\"}"))
                .andExpect(status().isPaymentRequired());

        mockMvc.perform(get("/api/usage").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.usedTokens", is(40)));
    }

    @Test
    void oneUsersExhaustedTrialNeverBlocksAnother() throws Exception {
        startTrialLastMonth(aliceToken, alice.getId());
        usedLastMonth(alice.getId(), 30L);
        startTrialLastMonth(bobToken, bob.getId());

        mockMvc.perform(get("/api/usage").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(jsonPath("$.usedTokens", is(0)))
                .andExpect(jsonPath("$.remainingTokens", is(30)));
        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().isOk());

        // Et la consommation reportée d'Alice n'a pas bougé d'un jeton.
        assertThat(usageCounterRepository.findByUserIdAndPeriodStart(alice.getId(), previousPeriod)
                .orElseThrow().getBilledTokens()).isEqualTo(30L);
    }
}
