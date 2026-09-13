package fr.claudegateway.billing;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration de l'option Vigie et des Gold par espace (F-107 / SF-107-03). Fournisseur dormant
 * dans le profil de test : les refus locaux sont observables, le chemin qui l'atteint répond 503.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class VigieOptionBillingApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private JwtService jwtService;

    private User alice;
    private String aliceToken;

    @BeforeEach
    void setUp() {
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        alice = userRepository.save(User.builder().email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);
    }

    private void subscribe(User user, PlanCode plan, SubscriptionStatus status, SubscriptionStatus vigieOption) {
        Subscription subscription = subscriptionRepository.findByUserId(user.getId())
                .orElseGet(() -> Subscription.builder().userId(user.getId()).build());
        subscription.setPlanCode(plan);
        subscription.setStatus(status);
        subscription.setTeamsOptionStatus(vigieOption);
        subscription.setVigieOptionStripeSubscriptionId(vigieOption == null ? null : "sub_vigie_secret");
        subscriptionRepository.save(subscription);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    @Test
    @DisplayName("Solo : 69 € de configuration, pas de droit, indisponible sans price")
    void soloSeesTheOptionAt69() throws Exception {
        subscribe(alice, PlanCode.SOLO, SubscriptionStatus.ACTIVE, null);

        mockMvc.perform(get("/api/billing/vigie-option").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceEur", is("69")))
                .andExpect(jsonPath("$.entitled", is(false)))
                .andExpect(jsonPath("$.includedInPlan", is(false)))
                .andExpect(jsonPath("$.status", nullValue()))
                .andExpect(jsonPath("$.available", is(false)))
                .andExpect(jsonPath("$.goldCarrier", is(false)));
    }

    @Test
    void goldVigieSeesItIncludedAndGoldForgeIsFlagged() throws Exception {
        subscribe(alice, PlanCode.GOLD_VIGIE, SubscriptionStatus.ACTIVE, null);
        mockMvc.perform(get("/api/billing/vigie-option").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(jsonPath("$.includedInPlan", is(true)))
                .andExpect(jsonPath("$.entitled", is(true)));

        subscribe(alice, PlanCode.GOLD, SubscriptionStatus.ACTIVE, null);
        mockMvc.perform(get("/api/billing/vigie-option").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(jsonPath("$.includedInPlan", is(false)))
                .andExpect(jsonPath("$.goldCarrier", is(true)));
    }

    @Test
    @DisplayName("Gold complet ouvre la Forge et la Vigie de bout en bout")
    void goldCompleteOpensBothSpaces() throws Exception {
        subscribe(alice, PlanCode.GOLD_COMPLETE, SubscriptionStatus.ACTIVE, null);

        mockMvc.perform(get("/api/billing/atelier-option").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(jsonPath("$.includedInPlan", is(true)));
        mockMvc.perform(get("/api/billing/vigie-option").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(jsonPath("$.includedInPlan", is(true)));
    }

    @Test
    void aVigieOptionSoloIsEntitledAndNoProviderIdLeaks() throws Exception {
        subscribe(alice, PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE);

        String body = mockMvc.perform(get("/api/billing/vigie-option").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entitled", is(true)))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("sub_vigie_secret");
    }

    @Test
    void checkoutRefusals() throws Exception {
        subscribe(alice, PlanCode.GOLD_COMPLETE, SubscriptionStatus.ACTIVE, null);
        mockMvc.perform(post("/api/billing/vigie-option/checkout").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("vigie_option_included")));

        subscribe(alice, null, SubscriptionStatus.TRIALING, null);
        mockMvc.perform(post("/api/billing/vigie-option/checkout").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("no_active_subscription")));

        subscribe(alice, PlanCode.PRO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE);
        mockMvc.perform(post("/api/billing/vigie-option/checkout").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("vigie_option_already_active")));

        subscribe(alice, PlanCode.BYOK, SubscriptionStatus.ACTIVE, null);
        mockMvc.perform(post("/api/billing/vigie-option/checkout").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", is("billing_unavailable")));
    }

    @Test
    void cancelWithoutOptionIsRefused() throws Exception {
        subscribe(alice, PlanCode.SOLO, SubscriptionStatus.ACTIVE, null);

        mockMvc.perform(post("/api/billing/vigie-option/cancel").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("vigie_option_not_active")));
    }

    @Test
    void theThreeEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/billing/vigie-option").contextPath("/api"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/billing/vigie-option/checkout").contextPath("/api"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/billing/vigie-option/cancel").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Sans price, ni Gold Vigie ni Gold complet ne sont listés ; Gold s'affiche « Gold Forge »")
    void spaceGoldsAreNotListedWithoutPrice() throws Exception {
        String body = mockMvc.perform(get("/api/billing/plans").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("GOLD_VIGIE").doesNotContain("GOLD_COMPLETE");
    }
}
