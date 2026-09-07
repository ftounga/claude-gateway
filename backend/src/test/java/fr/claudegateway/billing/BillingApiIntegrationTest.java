package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.quota.QuotaProperties;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration des endpoints billing (SF-09-01) : catalogue, provisionnement idempotent de
 * l'essai, authentification, isolation {@code user_id}, non-fuite des identifiants Stripe.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BillingApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private QuotaProperties quotaProperties;

    private String aliceToken;
    private User alice;

    @BeforeEach
    void setUp() {
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        alice = userRepository.save(User.builder()
                .email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);
    }

    @Test
    void listsPlanCatalog() throws Exception {
        mockMvc.perform(get("/api/billing/plans").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans", notNullValue()))
                // Seuls les plans avec un price configuré sont exposés (SOLO, PRO, BYOK) ; DAILY et
                // GOLD n'en ont pas en profil de test, ils sont exclus.
                .andExpect(jsonPath("$.plans.length()", is(3)))
                .andExpect(jsonPath("$.plans[0].code", notNullValue()))
                .andExpect(jsonPath("$.plans[0].providerMode", notNullValue()))
                .andExpect(jsonPath("$.plans[0].tokens", org.hamcrest.Matchers.greaterThan(0)));
    }

    @Test
    void provisionsTrialOnFirstAccessAndIsIdempotent() throws Exception {
        mockMvc.perform(get("/api/billing/subscription").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("TRIALING")))
                .andExpect(jsonPath("$.planCode", nullValue()))
                .andExpect(jsonPath("$.trialEndsAt", notNullValue()))
                // Les identifiants Stripe ne doivent jamais apparaître dans la réponse.
                .andExpect(jsonPath("$.stripeCustomerId").doesNotExist())
                .andExpect(jsonPath("$.stripeSubscriptionId").doesNotExist());

        // Second appel : même abonnement, aucune ligne supplémentaire.
        mockMvc.perform(get("/api/billing/subscription").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("TRIALING")));

        assertThat(subscriptionRepository.findAll()).hasSize(1);
        assertThat(subscriptionRepository.findAll().get(0).getUserId()).isEqualTo(alice.getId());
    }

    // ------------------------------------------------ F-43 / SF-43-01 — engagement annuel

    @Test
    void exposesYearlyOfferOnPlansThatHaveOneFullyConfigured() throws Exception {
        // SOLO a un price annuel ET un montant annuel en profil de test : l'offre est proposable.
        mockMvc.perform(get("/api/billing/plans").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans[?(@.code=='SOLO')].yearlyAvailable", contains(true)))
                .andExpect(jsonPath("$.plans[?(@.code=='SOLO')].yearlyPriceEur", contains("240")));
    }

    @Test
    void planWithoutYearlyPriceStaysMonthlyOnly() throws Exception {
        // PRO n'a aucun price annuel : il reste listé, au mois, sans jamais annoncer d'annuel.
        mockMvc.perform(get("/api/billing/plans").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans[?(@.code=='PRO')].yearlyAvailable", contains(false)))
                .andExpect(jsonPath("$.plans[?(@.code=='PRO')].yearlyPriceEur", contains(nullValue())))
                .andExpect(jsonPath("$.plans[?(@.code=='PRO')].priceEur", contains("99")));
    }

    @Test
    void halfConfiguredYearlyOfferIsNeverProposed() throws Exception {
        // BYOK a un price annuel mais AUCUN montant d'affichage annuel : proposer un bouton d'achat
        // sans prix serait pire que ne rien proposer.
        mockMvc.perform(get("/api/billing/plans").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans[?(@.code=='BYOK')].yearlyAvailable", contains(false)))
                .andExpect(jsonPath("$.plans[?(@.code=='BYOK')].yearlyPriceEur", contains(nullValue())));
    }

    @Test
    void quotaStaysMonthlyOnAPlanOfferedYearly() throws Exception {
        // LA règle de F-43, figée ici : l'engagement peut être annuel, l'allocation reste MENSUELLE.
        // Le catalogue annonce pour SOLO le quota mensuel configuré, pas douze fois celui-ci.
        long monthlyQuota = quotaProperties.tokensForPlan(PlanCode.SOLO);

        mockMvc.perform(get("/api/billing/plans").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans[?(@.code=='SOLO')].yearlyAvailable", contains(true)))
                .andExpect(jsonPath("$.plans[?(@.code=='SOLO')].tokens",
                        contains((int) monthlyQuota)));
    }

    @Test
    void neverLeaksAnyStripePriceIdIncludingYearlyOnes() throws Exception {
        String body = mockMvc.perform(get("/api/billing/plans").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("price_solo_test")
                .doesNotContain("price_solo_yearly_test")
                .doesNotContain("price_byok_yearly_test");
    }

    @Test
    void planCatalogIsGlobalAndCarriesNoUserData() throws Exception {
        // Isolation : le catalogue ne lit AUCUNE donnée d'utilisateur. Deux utilisateurs distincts
        // obtiennent donc exactement la même réponse — aucune donnée d'un tiers ne peut y fuir.
        User bob = userRepository.save(User.builder()
                .email("bob-plans@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        String bobToken = jwtService.generateToken(bob);

        String aliceBody = mockMvc.perform(get("/api/billing/plans").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String bobBody = mockMvc.perform(get("/api/billing/plans").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(aliceBody).isEqualTo(bobBody);
    }

    @Test
    void rejectsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/billing/subscription").contextPath("/api"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/billing/plans").contextPath("/api"))
                .andExpect(status().isUnauthorized());
        assertThat(subscriptionRepository.findAll()).isEmpty();
    }

    @Test
    void isolatesSubscriptionsPerUser() throws Exception {
        User bob = userRepository.save(User.builder()
                .email("bob@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        String bobToken = jwtService.generateToken(bob);

        mockMvc.perform(get("/api/billing/subscription").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/billing/subscription").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk());

        assertThat(subscriptionRepository.findByUserId(alice.getId())).isPresent();
        assertThat(subscriptionRepository.findByUserId(bob.getId())).isPresent();
        assertThat(subscriptionRepository.findByUserId(alice.getId()).get().getId())
                .isNotEqualTo(subscriptionRepository.findByUserId(bob.getId()).get().getId());
    }
}
