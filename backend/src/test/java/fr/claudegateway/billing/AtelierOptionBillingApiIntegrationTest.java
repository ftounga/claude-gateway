package fr.claudegateway.billing;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration des endpoints d'option Atelier (F-40 / SF-40-02) : description, refus de
 * souscription, résiliation, authentification et isolation {@code user_id}.
 *
 * <p>Le fournisseur de paiement est <b>dormant</b> dans le profil de test (aucune clé) : les refus
 * qui se décident <i>avant</i> lui sont donc pleinement observables, et le seul chemin qui l'atteint
 * répond 503 — ce qui est exactement le comportement attendu d'une option non configurée.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AtelierOptionBillingApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private JwtService jwtService;

    private User alice;
    private String aliceToken;
    private User bob;
    private String bobToken;

    @BeforeEach
    void setUp() {
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        alice = userRepository.save(User.builder().email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);
        bob = userRepository.save(User.builder().email("bob@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        bobToken = jwtService.generateToken(bob);
    }

    private void subscribe(User user, PlanCode plan, SubscriptionStatus status,
            SubscriptionStatus optionStatus, String optionSubId) {
        Subscription subscription = subscriptionRepository.findByUserId(user.getId())
                .orElseGet(() -> Subscription.builder().userId(user.getId()).build());
        subscription.setPlanCode(plan);
        subscription.setStatus(status);
        subscription.setAtelierOptionStatus(optionStatus);
        subscription.setAtelierOptionStripeSubscriptionId(optionSubId);
        subscriptionRepository.save(subscription);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    // ------------------------------------------------------------------- description

    @Test
    @DisplayName("Le prix affiché vient de la configuration (défaut 40 €), pas d'une constante d'écran")
    void describesTheOptionWithTheConfiguredPrice() throws Exception {
        subscribe(alice, PlanCode.SOLO, SubscriptionStatus.ACTIVE, null, null);

        mockMvc.perform(get("/api/billing/atelier-option").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priceEur", is("40")))
                .andExpect(jsonPath("$.entitled", is(false)))
                .andExpect(jsonPath("$.includedInPlan", is(false)))
                .andExpect(jsonPath("$.status", nullValue()))
                .andExpect(jsonPath("$.cancelAt", nullValue()))
                // Fournisseur dormant dans le profil de test : l'option n'est pas souscriptible.
                .andExpect(jsonPath("$.available", is(false)));
    }

    @Test
    void goldSeesTheOptionAsAlreadyIncluded() throws Exception {
        subscribe(alice, PlanCode.GOLD, SubscriptionStatus.ACTIVE, null, null);

        mockMvc.perform(get("/api/billing/atelier-option").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.includedInPlan", is(true)))
                .andExpect(jsonPath("$.entitled", is(true)));
    }

    @Test
    void anOptionarySoloSeesTheRightOpen() throws Exception {
        subscribe(alice, PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE, "sub_opt_a");

        mockMvc.perform(get("/api/billing/atelier-option").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entitled", is(true)))
                .andExpect(jsonPath("$.includedInPlan", is(false)))
                .andExpect(jsonPath("$.status", is("ACTIVE")));
    }

    @Test
    @DisplayName("Aucun identifiant fournisseur ne sort jamais de l'API")
    void neverLeaksProviderIdentifiers() throws Exception {
        subscribe(alice, PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE, "sub_opt_secret");

        String body = mockMvc.perform(get("/api/billing/atelier-option").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(body).doesNotContain("sub_opt_secret");
    }

    // ------------------------------------------------------------------ souscription

    @Test
    void goldCannotBuyWhatItAlreadyHas() throws Exception {
        subscribe(alice, PlanCode.GOLD, SubscriptionStatus.ACTIVE, null, null);

        mockMvc.perform(post("/api/billing/atelier-option/checkout").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("atelier_option_included")));
    }

    @Test
    void aTrialUserIsToldToSubscribeAPlanFirst() throws Exception {
        mockMvc.perform(post("/api/billing/atelier-option/checkout").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("no_active_subscription")))
                .andExpect(jsonPath("$.message", notNullValue()));
    }

    @Test
    void anAlreadyOptionarySoloIsRefused() throws Exception {
        subscribe(alice, PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE, "sub_opt_a");

        mockMvc.perform(post("/api/billing/atelier-option/checkout").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("atelier_option_already_active")));
    }

    @Test
    @DisplayName("Option non configurée : 503, comme un plan sans price")
    void aDormantOptionAnswersUnavailable() throws Exception {
        subscribe(alice, PlanCode.SOLO, SubscriptionStatus.ACTIVE, null, null);

        mockMvc.perform(post("/api/billing/atelier-option/checkout").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error", is("billing_unavailable")));
    }

    // ------------------------------------------------------------------- résiliation

    @Test
    void cancelWithoutOptionIsRefused() throws Exception {
        subscribe(alice, PlanCode.SOLO, SubscriptionStatus.ACTIVE, null, null);

        mockMvc.perform(post("/api/billing/atelier-option/cancel").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("atelier_option_not_active")));
    }

    // ------------------------------------------------------ authentification & quota

    @Test
    void theThreeEndpointsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/billing/atelier-option").contextPath("/api"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/billing/atelier-option/checkout").contextPath("/api"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/billing/atelier-option/cancel").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("L'option ouvre un droit, jamais un jeton : le quota ne bouge pas")
    void theOptionDoesNotChangeTheQuota() throws Exception {
        subscribe(alice, PlanCode.SOLO, SubscriptionStatus.ACTIVE, null, null);
        String before = mockMvc.perform(get("/api/usage").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        long quotaBefore = JsonPath.parse(before).read("$.quotaTokens", Long.class);

        subscribe(alice, PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE, "sub_opt_a");

        mockMvc.perform(get("/api/usage").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotaTokens", is((int) quotaBefore)));
    }

    @Test
    @DisplayName("Bout en bout du droit : l'option ouvre l'Atelier sans qu'aucune règle ait changé")
    void theOptionOpensTheAtelier() throws Exception {
        subscribe(alice, PlanCode.SOLO, SubscriptionStatus.ACTIVE, null, null);
        mockMvc.perform(get("/api/workspaces").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isForbidden());

        subscribe(alice, PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE, "sub_opt_a");
        mockMvc.perform(get("/api/workspaces").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------ isolation utilisateur

    @Test
    @DisplayName("Isolation : l'option d'Alice n'apparaît jamais chez Bob")
    void bobNeverSeesAliceOption() throws Exception {
        subscribe(alice, PlanCode.SOLO, SubscriptionStatus.ACTIVE, SubscriptionStatus.ACTIVE, "sub_opt_a");
        subscribe(bob, PlanCode.SOLO, SubscriptionStatus.ACTIVE, null, null);

        mockMvc.perform(get("/api/billing/atelier-option").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(jsonPath("$.entitled", is(true)));
        mockMvc.perform(get("/api/billing/atelier-option").contextPath("/api")
                        .header("Authorization", bearer(bobToken)))
                .andExpect(jsonPath("$.entitled", is(false)))
                .andExpect(jsonPath("$.status", nullValue()));

        // Et la résiliation de Bob ne peut rien atteindre chez Alice : il n'a pas d'option.
        mockMvc.perform(post("/api/billing/atelier-option/cancel").contextPath("/api")
                        .header("Authorization", bearer(bobToken)))
                .andExpect(status().isConflict());
        UUID aliceId = alice.getId();
        org.assertj.core.api.Assertions
                .assertThat(subscriptionRepository.findByUserId(aliceId).orElseThrow()
                        .getAtelierOptionStatus())
                .isEqualTo(SubscriptionStatus.ACTIVE);
    }
}
