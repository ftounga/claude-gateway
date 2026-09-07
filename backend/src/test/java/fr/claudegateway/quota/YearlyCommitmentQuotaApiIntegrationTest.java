package fr.claudegateway.quota;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.BillingPeriod;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-43 / SF-43-02 — bout en bout : un abonné <b>engagé à l'année</b> voit un quota <b>mensuel</b>,
 * sur une <b>période mensuelle</b>.
 *
 * <p>C'est la promesse produit de la feature, vérifiée là où l'utilisateur la lit réellement, et
 * pas seulement dans le service qui la calcule. Un quota annuel exposerait à une consommation
 * intégrale dès le premier mois.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class YearlyCommitmentQuotaApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private UsageCounterRepository usageCounterRepository;

    @Autowired
    private QuotaProperties quotaProperties;

    @Autowired
    private JwtService jwtService;

    private String yearlyToken;
    private String monthlyToken;

    @BeforeEach
    void setUp() {
        usageCounterRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        yearlyToken = jwtService.generateToken(subscriber("yearly@example.com", BillingPeriod.YEARLY));
        monthlyToken = jwtService.generateToken(subscriber("monthly@example.com", BillingPeriod.MONTHLY));
    }

    private User subscriber(String email, BillingPeriod period) {
        User user = userRepository.save(User.builder()
                .email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        subscriptionRepository.save(Subscription.builder()
                .userId(user.getId())
                .status(SubscriptionStatus.ACTIVE)
                .planCode(PlanCode.SOLO)
                .billingPeriod(period)
                .stripeSubscriptionId("sub_" + email)
                .build());
        return user;
    }

    @Test
    void aYearlySubscriberSeesTheMonthlyAllocationOverTheCalendarMonth() throws Exception {
        long monthlyQuota = quotaProperties.tokensForPlan(PlanCode.SOLO);
        LocalDate firstOfMonth = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);

        mockMvc.perform(get("/api/usage").contextPath("/api")
                        .header("Authorization", "Bearer " + yearlyToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotaTokens", is((int) monthlyQuota)))
                // La période de consommation reste le mois calendaire UTC : payer douze mois
                // d'avance ne fabrique pas une fenêtre de douze mois.
                .andExpect(jsonPath("$.periodStart", is(firstOfMonth.toString())))
                .andExpect(jsonPath("$.periodEnd", is(firstOfMonth.plusMonths(1).toString())));
    }

    @Test
    void theCommitmentPeriodChangesNothingBetweenTwoSubscribersOfTheSamePlan() throws Exception {
        long monthlyQuota = quotaProperties.tokensForPlan(PlanCode.SOLO);

        mockMvc.perform(get("/api/usage").contextPath("/api")
                        .header("Authorization", "Bearer " + monthlyToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotaTokens", is((int) monthlyQuota)));

        mockMvc.perform(get("/api/usage").contextPath("/api")
                        .header("Authorization", "Bearer " + yearlyToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotaTokens", is((int) monthlyQuota)));
    }
}
