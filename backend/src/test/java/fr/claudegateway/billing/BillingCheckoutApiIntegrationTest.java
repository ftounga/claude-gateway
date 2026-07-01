package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

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
import fr.claudegateway.billing.provider.WebhookVerificationException;
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
        volatile RuntimeException webhookToThrow;

        void reset() {
            sessionToReturn = new CheckoutSession("https://checkout.stripe/test", "cs_1");
            eventToReturn = BillingEvent.unhandled();
            checkoutToThrow = null;
            webhookToThrow = null;
        }

        @Override
        public boolean isConfigured() {
            return true;
        }

        @Override
        public CheckoutSession createCheckoutSession(CheckoutCommand command) {
            if (checkoutToThrow != null) {
                throw checkoutToThrow;
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

    private User alice;
    private String aliceToken;

    @BeforeEach
    void setUp() {
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
                PlanCode.PRO, "active", null);

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
}
