package fr.claudegateway.billing;

import static org.assertj.core.api.Assertions.assertThat;
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
                .andExpect(jsonPath("$.plans.length()", is(3)))
                .andExpect(jsonPath("$.plans[0].code", notNullValue()))
                .andExpect(jsonPath("$.plans[0].providerMode", notNullValue()));
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
