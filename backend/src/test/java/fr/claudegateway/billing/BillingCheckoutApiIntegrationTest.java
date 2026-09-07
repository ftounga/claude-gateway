package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.provider.BillingEvent;
import fr.claudegateway.billing.provider.BillingEventType;
import fr.claudegateway.billing.provider.BillingProvider;
import fr.claudegateway.billing.provider.BillingProviderUnavailableException;
import fr.claudegateway.billing.provider.CheckoutCommand;
import fr.claudegateway.billing.provider.CheckoutSession;
import fr.claudegateway.billing.provider.TopUpCheckoutCommand;
import fr.claudegateway.billing.provider.WebhookVerificationException;
import fr.claudegateway.quota.UsageCounter;
import fr.claudegateway.quota.UsageCounterRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration checkout + webhook (SF-09-02). Le {@link BillingProvider} réel (Stripe) est
 * remplacé par un stub {@code @Primary} : on couvre le contrôleur, la sécurité (webhook public,
 * checkout protégé) et le service, sans réseau ni signature réelle.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BillingCheckoutApiIntegrationTest {

    static class StubBillingProvider implements BillingProvider {
        volatile CheckoutSession sessionToReturn = new CheckoutSession("https://checkout.stripe/test", "cs_1");
        volatile BillingEvent eventToReturn = BillingEvent.unhandled();
        volatile RuntimeException checkoutToThrow;
        volatile RuntimeException topUpCheckoutToThrow;
        volatile RuntimeException webhookToThrow;
        volatile String lastChangedSubId;
        volatile String lastChangedPriceId;
        volatile String lastCanceledSubId;
        volatile CheckoutCommand lastCheckoutCommand;

        void reset() {
            sessionToReturn = new CheckoutSession("https://checkout.stripe/test", "cs_1");
            eventToReturn = BillingEvent.unhandled();
            checkoutToThrow = null;
            topUpCheckoutToThrow = null;
            webhookToThrow = null;
            lastChangedSubId = null;
            lastChangedPriceId = null;
            lastCanceledSubId = null;
            lastCheckoutCommand = null;
        }

        @Override
        public CheckoutSession createAtelierOptionCheckoutSession(
                fr.claudegateway.billing.provider.AtelierOptionCheckoutCommand command) {
            if (checkoutToThrow != null) {
                throw checkoutToThrow;
            }
            return sessionToReturn;
        }

        @Override
        public java.time.OffsetDateTime scheduleSubscriptionCancellation(String providerSubscriptionId) {
            lastCanceledSubId = providerSubscriptionId;
            return java.time.OffsetDateTime.now().plusDays(20);
        }

        @Override
        public void changeSubscriptionPlan(fr.claudegateway.billing.provider.ChangePlanCommand command) {
            lastChangedSubId = command.stripeSubscriptionId();
            lastChangedPriceId = command.newPriceId();
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public CheckoutSession createCheckoutSession(CheckoutCommand command) {
            lastCheckoutCommand = command;
            if (checkoutToThrow != null) {
                throw checkoutToThrow;
            }
            return sessionToReturn;
        }

        @Override
        public CheckoutSession createTopUpCheckoutSession(TopUpCheckoutCommand command) {
            if (topUpCheckoutToThrow != null) {
                throw topUpCheckoutToThrow;
            }
            return sessionToReturn;
        }

        @Override
        public BillingEvent parseWebhookEvent(String payload, String signatureHeader) {
            if (webhookToThrow != null) {
                throw webhookToThrow;
            }
            return eventToReturn;
        }
    }

    @TestConfiguration
    static class StubConfig {
        @Bean
        @Primary
        StubBillingProvider stubBillingProvider() {
            return new StubBillingProvider();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private StubBillingProvider stubBillingProvider;

    @Autowired
    private UsageCounterRepository usageCounterRepository;

    @Autowired
    private ProcessedBillingEventRepository processedBillingEventRepository;

    private User alice;
    private String aliceToken;

    @BeforeEach
    void setUp() {
        processedBillingEventRepository.deleteAll();
        usageCounterRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        stubBillingProvider.reset();

        alice = userRepository.save(User.builder()
                .email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);
    }

    @Test
    void createsCheckoutSession() throws Exception {
        mockMvc.perform(post("/api/billing/checkout").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"PRO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutUrl", containsString("checkout.stripe")));
    }

    @Test
    void rejectsUnknownPlan() throws Exception {
        mockMvc.perform(post("/api/billing/checkout").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"PLATINUM\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }

    @Test
    void rejectsCheckoutWithoutJwt() throws Exception {
        mockMvc.perform(post("/api/billing/checkout").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"PRO\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mapsProviderUnavailableTo503() throws Exception {
        stubBillingProvider.checkoutToThrow = new BillingProviderUnavailableException("dormant");
        mockMvc.perform(post("/api/billing/checkout").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"PRO\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", is("billing_unavailable")));
    }

    @Test
    void webhookIsPublicAndActivatesSubscription() throws Exception {
        // Alice possède déjà un essai.
        subscriptionRepository.save(Subscription.builder()
                .userId(alice.getId()).status(SubscriptionStatus.TRIALING)
                .trialEndsAt(OffsetDateTime.now().plusDays(14)).build());
        stubBillingProvider.eventToReturn = new BillingEvent(
                BillingEventType.CHECKOUT_COMPLETED, alice.getId(), "cus_1", "sub_1",
                PlanCode.PRO, "active", null, "evt_1", null);

        // Pas d'Authorization : l'endpoint est public (authentifié par signature).
        mockMvc.perform(post("/api/webhook/stripe").contextPath("/api")
                        .header("Stripe-Signature", "t=1,v1=abc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"evt_1\"}"))
                .andExpect(status().isOk());

        Subscription updated = subscriptionRepository.findByUserId(alice.getId()).orElseThrow();
        assertThat(updated.getStatus()).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(updated.getPlanCode()).isEqualTo(PlanCode.PRO);
        assertThat(updated.getStripeSubscriptionId()).isEqualTo("sub_1");
    }

    @Test
    void webhookRejectsInvalidSignatureWith400() throws Exception {
        stubBillingProvider.webhookToThrow = new WebhookVerificationException("bad sig");
        mockMvc.perform(post("/api/webhook/stripe").contextPath("/api")
                        .header("Stripe-Signature", "bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"evt_1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("invalid_signature")));
    }

    // --- Rachat de tokens (top-up, SF-21-02) ---

    @Test
    void listsTopUpPacks() throws Exception {
        mockMvc.perform(get("/api/billing/topups").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                // Deux packs disponibles (Recharge 200 k + Recharge 1 M), ordre non contraint.
                .andExpect(jsonPath("$.packs[*].code", org.hamcrest.Matchers.hasItems("DAY", "STANDARD")))
                .andExpect(jsonPath("$.packs[*].tokens", org.hamcrest.Matchers.hasItems(200000, 1000000)));
    }

    @Test
    void topUpEndpointsRequireJwt() throws Exception {
        mockMvc.perform(get("/api/billing/topups").contextPath("/api"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/billing/topup/checkout").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packCode\":\"STANDARD\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createsTopUpCheckoutSession() throws Exception {
        mockMvc.perform(post("/api/billing/topup/checkout").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packCode\":\"STANDARD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutUrl", containsString("checkout.stripe")));
    }

    @Test
    void rejectsUnknownTopUpPack() throws Exception {
        mockMvc.perform(post("/api/billing/topup/checkout").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"packCode\":\"GHOST\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }

    @Test
    void topUpWebhookCreditsBonusTokensOnceAndIsIsolated() throws Exception {
        User bob = userRepository.save(User.builder()
                .email("bob@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());

        stubBillingProvider.eventToReturn = new BillingEvent(
                BillingEventType.TOPUP_COMPLETED, alice.getId(), "cus_1", null,
                null, null, null, "evt_topup_1", "STANDARD");

        // Deux livraisons du même événement (rejeu Stripe).
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/webhook/stripe").contextPath("/api")
                            .header("Stripe-Signature", "t=1,v1=abc")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"id\":\"evt_topup_1\"}"))
                    .andExpect(status().isOk());
        }

        // Alice créditée une seule fois (idempotence) ; Bob non affecté (isolation).
        LocalDate period = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        UsageCounter aliceCounter =
                usageCounterRepository.findByUserIdAndPeriodStart(alice.getId(), period).orElseThrow();
        assertThat(aliceCounter.getBonusTokens()).isEqualTo(1_000_000L);
        assertThat(usageCounterRepository.findByUserIdAndPeriodStart(bob.getId(), period)).isEmpty();
    }

    // ---- Changement de plan (upgrade/downgrade, SF-21-05) ----

    @Test
    void listsEnrichedPlansExcludingUnpricedOnes() throws Exception {
        mockMvc.perform(get("/api/billing/plans").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                // Seuls SOLO/PRO ont un price configuré (test) ; DAILY est hors catalogue (SF-09-04).
                .andExpect(jsonPath("$.plans[*].code", org.hamcrest.Matchers.hasItems("SOLO", "PRO")))
                .andExpect(jsonPath("$.plans[*].code",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("DAILY"))))
                .andExpect(jsonPath("$.plans[0].tokens", org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath("$.plans[0].priceEur", is(org.hamcrest.Matchers.notNullValue())));
    }

    @Test
    void changePlanReturns409WhenStillInTrial() throws Exception {
        // Alice n'a pas d'abonnement actif (essai provisionné à la volée, sans stripeSubscriptionId).
        mockMvc.perform(post("/api/billing/subscription/change").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"PRO\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("no_active_subscription")));
    }

    @Test
    void changePlanUpdatesActiveSubscription() throws Exception {
        subscriptionRepository.save(Subscription.builder()
                .userId(alice.getId()).status(SubscriptionStatus.ACTIVE).planCode(PlanCode.SOLO)
                .stripeCustomerId("cus_alice").stripeSubscriptionId("sub_alice").build());

        mockMvc.perform(post("/api/billing/subscription/change").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"PRO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode", is("PRO")));

        // Le fournisseur a été appelé avec l'abonnement et le price cible (résolus côté serveur).
        assertThat(stubBillingProvider.lastChangedSubId).isEqualTo("sub_alice");
        assertThat(stubBillingProvider.lastChangedPriceId).isEqualTo("price_pro_test");
    }
    // ------------------------------------------------ F-43 / SF-43-02 — souscrire à l'année

    private static String checkoutBody(String planCode, String period) {
        return period == null
                ? "{\"planCode\":\"" + planCode + "\"}"
                : "{\"planCode\":\"" + planCode + "\",\"period\":\"" + period + "\"}";
    }

    @Test
    void checkoutWithoutAPeriodStillBuysTheMonthlyPrice() throws Exception {
        // Non-régression du contrat d'origine : le frontend actuel n'envoie aucune périodicité.
        mockMvc.perform(post("/api/billing/checkout").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody("SOLO", null)))
                .andExpect(status().isOk());

        assertThat(stubBillingProvider.lastCheckoutCommand.period()).isEqualTo(BillingPeriod.MONTHLY);
        assertThat(stubBillingProvider.lastCheckoutCommand.priceId()).isEqualTo("price_solo_test");
    }

    @Test
    void checkoutWithTheYearlyPeriodBuysTheYearlyPrice() throws Exception {
        mockMvc.perform(post("/api/billing/checkout").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody("SOLO", "YEARLY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutUrl", containsString("checkout.stripe")));

        assertThat(stubBillingProvider.lastCheckoutCommand.period()).isEqualTo(BillingPeriod.YEARLY);
        assertThat(stubBillingProvider.lastCheckoutCommand.priceId()).isEqualTo("price_solo_yearly_test");
    }

    @Test
    void checkoutRefusesTheYearlyPeriodOnAPlanWithoutAYearlyOffer() throws Exception {
        // PRO n'a pas de price annuel en profil de test : 409, et le fournisseur n'est pas appelé.
        mockMvc.perform(post("/api/billing/checkout").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody("PRO", "YEARLY")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("yearly_not_available")));

        assertThat(stubBillingProvider.lastCheckoutCommand).isNull();
    }

    @Test
    void checkoutRefusesAnUnknownPeriod() throws Exception {
        mockMvc.perform(post("/api/billing/checkout").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody("SOLO", "WEEKLY")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));

        assertThat(stubBillingProvider.lastCheckoutCommand).isNull();
    }

    @Test
    void changePlanToTheYearlyPeriodSendsTheYearlyPriceAndIsVisibleOnTheSubscription() throws Exception {
        Subscription active = subscriptionRepository.save(Subscription.builder()
                .userId(alice.getId()).status(SubscriptionStatus.ACTIVE).planCode(PlanCode.PRO)
                .stripeSubscriptionId("sub_alice").build());

        mockMvc.perform(post("/api/billing/subscription/change").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody("SOLO", "YEARLY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billingPeriod", is("YEARLY")))
                .andExpect(jsonPath("$.planCode", is("SOLO")))
                // Les identifiants fournisseur restent internes, engagement annuel ou pas.
                .andExpect(jsonPath("$.stripeSubscriptionId").doesNotExist());

        assertThat(stubBillingProvider.lastChangedPriceId).isEqualTo("price_solo_yearly_test");
        assertThat(subscriptionRepository.findById(active.getId()).orElseThrow().getBillingPeriod())
                .isEqualTo(BillingPeriod.YEARLY);
    }

    @Test
    void aRefusedYearlyChangeLeavesTheSubscriptionUntouched() throws Exception {
        Subscription active = subscriptionRepository.save(Subscription.builder()
                .userId(alice.getId()).status(SubscriptionStatus.ACTIVE).planCode(PlanCode.SOLO)
                .billingPeriod(BillingPeriod.MONTHLY)
                .stripeSubscriptionId("sub_alice").build());

        mockMvc.perform(post("/api/billing/subscription/change").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody("PRO", "YEARLY")))
                .andExpect(status().isConflict());

        Subscription reloaded = subscriptionRepository.findById(active.getId()).orElseThrow();
        assertThat(reloaded.getPlanCode()).isEqualTo(PlanCode.SOLO);
        assertThat(reloaded.getBillingPeriod()).isEqualTo(BillingPeriod.MONTHLY);
        assertThat(stubBillingProvider.lastChangedPriceId).isNull();
    }

    @Test
    void subscriptionExposesNoCommitmentUntilOneIsRecorded() throws Exception {
        mockMvc.perform(get("/api/billing/subscription").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("TRIALING")))
                .andExpect(jsonPath("$.billingPeriod").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void oneUsersYearlyCommitmentNeverReachesAnother() throws Exception {
        User bob = userRepository.save(User.builder()
                .email("bob-yearly@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        String bobToken = jwtService.generateToken(bob);
        subscriptionRepository.save(Subscription.builder()
                .userId(alice.getId()).status(SubscriptionStatus.ACTIVE).planCode(PlanCode.SOLO)
                .stripeSubscriptionId("sub_alice").build());

        mockMvc.perform(post("/api/billing/subscription/change").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(checkoutBody("SOLO", "YEARLY")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billingPeriod", is("YEARLY")));

        // Bob n'a rien souscrit : son abonnement ne porte aucun engagement, et son quota est intact.
        mockMvc.perform(get("/api/billing/subscription").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billingPeriod").value(org.hamcrest.Matchers.nullValue()));

        assertThat(subscriptionRepository.findByUserId(bob.getId()).orElseThrow().getBillingPeriod())
                .isNull();
    }

}
