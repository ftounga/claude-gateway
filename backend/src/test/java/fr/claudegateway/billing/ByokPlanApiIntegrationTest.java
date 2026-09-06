package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
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

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ProviderFileReference;
import fr.claudegateway.ai.ProviderFileUpload;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.byok.ByokKeyCipher;
import fr.claudegateway.byok.ByokProvider;
import fr.claudegateway.byok.EncryptedKey;
import fr.claudegateway.byok.UserApiKey;
import fr.claudegateway.byok.UserApiKeyRepository;
import fr.claudegateway.billing.provider.AtelierOptionCheckoutCommand;
import fr.claudegateway.billing.provider.BillingEvent;
import fr.claudegateway.billing.provider.BillingProvider;
import fr.claudegateway.billing.provider.ChangePlanCommand;
import fr.claudegateway.billing.provider.CheckoutCommand;
import fr.claudegateway.billing.provider.CheckoutSession;
import fr.claudegateway.billing.provider.TopUpCheckoutCommand;
import fr.claudegateway.quota.UsageCounter;
import fr.claudegateway.quota.UsageCounterRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration de l'offre BYOK (F-41 / SF-41-01) sur {@code /api/**}.
 *
 * <p>Ils figent la distinction qui fait la feature : un abonné BYOK et un abonné résilié résolvent
 * <b>tous les deux</b> un quota de 0 jeton, mais le premier doit passer et le second être bloqué.
 * Les deux utilisateurs vivent dans le même test, avec la même consommation déjà enregistrée : c'est
 * la seule mise en scène où une confusion entre les deux zéros se voit immédiatement.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ByokPlanApiIntegrationTest {

    /** Fournisseur IA bouchonné : 12 + 8 = 20 tokens par appel, aucun réseau. */
    static class StubAIProvider implements AIProvider {
        @Override
        public ChatCompletionResult complete(ChatCompletionRequest request) {
            return new ChatCompletionResult("Réponse", request.model(), 12, 8);
        }

        @Override
        public ProviderFileReference uploadFile(ProviderFileUpload upload) {
            return new ProviderFileReference("file_stub");
        }
    }

    /** Fournisseur de paiement bouchonné : le checkout n'appelle jamais Stripe en test. */
    static class StubBillingProvider implements BillingProvider {
        volatile CheckoutCommand lastCheckout;

        @Override
        public CheckoutSession createCheckoutSession(CheckoutCommand command) {
            lastCheckout = command;
            return new CheckoutSession("https://checkout.stripe/byok", "cs_byok");
        }

        @Override
        public CheckoutSession createTopUpCheckoutSession(TopUpCheckoutCommand command) {
            return new CheckoutSession("https://checkout.stripe/topup", "cs_topup");
        }

        @Override
        public CheckoutSession createAtelierOptionCheckoutSession(AtelierOptionCheckoutCommand command) {
            return new CheckoutSession("https://checkout.stripe/option", "cs_option");
        }

        @Override
        public void changeSubscriptionPlan(ChangePlanCommand command) {
            // Sans objet ici.
        }

        @Override
        public java.time.OffsetDateTime scheduleSubscriptionCancellation(String providerSubscriptionId) {
            return java.time.OffsetDateTime.now().plusDays(20);
        }

        @Override
        public BillingEvent parseWebhookEvent(String payload, String signatureHeader) {
            return BillingEvent.unhandled();
        }

        @Override
        public boolean isConfigured() {
            return true;
        }
    }

    @TestConfiguration
    static class StubsConfig {
        @Bean
        @Primary
        StubAIProvider stubAIProvider() {
            return new StubAIProvider();
        }

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
    private UsageCounterRepository usageCounterRepository;

    @Autowired
    private UserApiKeyRepository userApiKeyRepository;

    @Autowired
    private ByokKeyCipher cipher;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private StubBillingProvider stubBillingProvider;

    private User byokUser;
    private String byokToken;
    private User canceledUser;
    private String canceledToken;

    @BeforeEach
    void setUp() {
        usageCounterRepository.deleteAll();
        userApiKeyRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        byokUser = userRepository.save(User.builder()
                .email("byok@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        byokToken = jwtService.generateToken(byokUser);

        canceledUser = userRepository.save(User.builder()
                .email("resilie@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        canceledToken = jwtService.generateToken(canceledUser);
    }

    /**
     * Dépose une clé BYOK active. Depuis SF-41-02, une offre BYOK <b>sans</b> clé est refusée
     * (409 {@code byok_key_required}) : les scénarios de quota ci-dessous doivent donc partir d'un
     * client complet, sinon ils mesureraient l'autre refus.
     */
    private void giveActiveKey(User user) {
        EncryptedKey encrypted = cipher.encrypt("sk-ant-cle-du-client-4321");
        userApiKeyRepository.save(UserApiKey.builder()
                .userId(user.getId()).provider(ByokProvider.ANTHROPIC).active(true)
                .encryptedDataKey(encrypted.encryptedDataKey())
                .cipherIv(encrypted.iv()).ciphertext(encrypted.ciphertext())
                .keyLast4("4321").build());
    }

    private void giveSubscription(User user, PlanCode plan, SubscriptionStatus status) {
        subscriptionRepository.save(Subscription.builder()
                .userId(user.getId()).planCode(plan).status(status)
                .stripeCustomerId("cus_" + user.getId()).stripeSubscriptionId("sub_" + user.getId())
                .build());
    }

    /** Consommation déjà enregistrée sur la période : le piège du test `used >= quota`. */
    private void recordConsumption(User user, long tokens) {
        usageCounterRepository.save(UsageCounter.builder()
                .userId(user.getId())
                .periodStart(LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1))
                .inputTokens(tokens).outputTokens(0L).build());
    }

    @Test
    void catalogExposesTheByokPlanWithNoTokensAndItsConfiguredPrice() throws Exception {
        mockMvc.perform(get("/api/billing/plans").contextPath("/api")
                        .header("Authorization", "Bearer " + byokToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plans[*].code", hasItem("BYOK")))
                .andExpect(jsonPath("$.plans[?(@.code == 'BYOK')].providerMode", hasItem("BYOK")))
                .andExpect(jsonPath("$.plans[?(@.code == 'BYOK')].period", hasItem("MONTHLY")))
                // Le contrat de l'offre, lisible dans la réponse : aucun jeton alloué.
                .andExpect(jsonPath("$.plans[?(@.code == 'BYOK')].tokens", hasItem(0)))
                // Prix venu de la configuration (APP_BILLING_BYOK_PRICE), jamais d'une constante.
                .andExpect(jsonPath("$.plans[?(@.code == 'BYOK')].priceEur", hasItem("29")));
    }

    @Test
    void byokPlanIsSubscribableThroughTheGenericCheckout() throws Exception {
        mockMvc.perform(post("/api/billing/checkout").contextPath("/api")
                        .header("Authorization", "Bearer " + byokToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"BYOK\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkoutUrl", is("https://checkout.stripe/byok")));

        // Le price ID est résolu côté serveur depuis la configuration : le client ne le fournit jamais.
        assertThat(stubBillingProvider.lastCheckout.priceId()).isEqualTo("price_byok_test");
        assertThat(stubBillingProvider.lastCheckout.plan().code()).isEqualTo(PlanCode.BYOK);
    }

    @Test
    void byokSubscriberIsNeverBlockedByTheZeroTokenQuota() throws Exception {
        giveSubscription(byokUser, PlanCode.BYOK, SubscriptionStatus.ACTIVE);
        giveActiveKey(byokUser);
        // Consommation déjà enregistrée : sans la distinction F-41, `used (500) >= quota (0)` bloquerait.
        recordConsumption(byokUser, 500L);

        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", "Bearer " + byokToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void canceledSubscriberResolvingTheSameZeroIsStillBlocked() throws Exception {
        // Non-régression du fail-closed : l'autre zéro, celui de l'impayé, bloque toujours.
        giveSubscription(canceledUser, PlanCode.PRO, SubscriptionStatus.CANCELED);
        recordConsumption(canceledUser, 500L);

        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", "Bearer " + canceledToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error", is("quota_exceeded")));
    }

    @Test
    void theByokDerogationNeverLeaksToAnotherUser() throws Exception {
        // Isolation : l'abonnement BYOK d'un utilisateur ne débloque personne d'autre. Les deux
        // comptes vivent côte à côte, chacun résolu depuis SON user_id (contexte de sécurité).
        giveSubscription(byokUser, PlanCode.BYOK, SubscriptionStatus.ACTIVE);
        giveActiveKey(byokUser);
        giveSubscription(canceledUser, PlanCode.BYOK, SubscriptionStatus.CANCELED);

        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", "Bearer " + byokToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", "Bearer " + canceledToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error", is("quota_exceeded")));
    }

    @Test
    void byokSubscriberGetsTheAtelierWithoutBuyingTheOption() throws Exception {
        giveSubscription(byokUser, PlanCode.BYOK, SubscriptionStatus.ACTIVE);

        mockMvc.perform(get("/api/workspaces").contextPath("/api")
                        .header("Authorization", "Bearer " + byokToken))
                .andExpect(status().isOk());

        // ... et l'écran de facturation le dit : l'option n'a pas lieu d'être sur cette offre.
        mockMvc.perform(get("/api/billing/atelier-option").contextPath("/api")
                        .header("Authorization", "Bearer " + byokToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entitled", is(true)))
                .andExpect(jsonPath("$.includedInPlan", is(true)));
    }

    @Test
    void subscriptionTellsTheScreenWhoPaysForTheTokens() throws Exception {
        // F-41 / SF-41-03 : l'écran ne déduit pas l'offre d'un code de plan — il lit la décision que
        // le serveur a déjà prise. Sans ce champ, il faudrait la re-dériver, et diverger un jour.
        giveSubscription(byokUser, PlanCode.BYOK, SubscriptionStatus.ACTIVE);
        mockMvc.perform(get("/api/billing/subscription").contextPath("/api")
                        .header("Authorization", "Bearer " + byokToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerKeyBilled", is(true)));

        // Offre Hosted : inchangé, la plateforme paie les jetons.
        giveSubscription(canceledUser, PlanCode.PRO, SubscriptionStatus.ACTIVE);
        mockMvc.perform(get("/api/billing/subscription").contextPath("/api")
                        .header("Authorization", "Bearer " + canceledToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerKeyBilled", is(false)));
    }

    @Test
    void aCanceledByokSubscriptionNoLongerClaimsToBeCustomerKeyBilled() throws Exception {
        // Sinon l'écran masquerait la jauge de quota à un compte bloqué, et lui cacherait la raison
        // pour laquelle il est bloqué.
        giveSubscription(byokUser, PlanCode.BYOK, SubscriptionStatus.CANCELED);

        mockMvc.perform(get("/api/billing/subscription").contextPath("/api")
                        .header("Authorization", "Bearer " + byokToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customerKeyBilled", is(false)));
    }
}
