package fr.claudegateway.billing.seat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>La garantie de non-régression de F-65</b> : avec la configuration livrée — celle où le PO n'a
 * rien renseigné — trois postes donnent exactement le quota d'avant.
 *
 * <p>Aucune surcharge de propriété ici, et c'est tout l'objet du test : le mécanisme est livré
 * <b>inerte</b>. Les postes sont comptés et montrés, le supplément n'est pas facturé, aucun jeton
 * n'est apporté à personne.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SeatDefaultsApiIntegrationTest {

    private static final long SOLO_TOKENS = 1_000_000L;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private HostSeatMonthRepository seatMonthRepository;
    @Autowired private JwtService jwtService;

    private String token;

    @BeforeEach
    void setUp() {
        seatMonthRepository.deleteAll();
        hostRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        User user = userRepository.save(User.builder().email("defaults-seats@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        token = jwtService.generateToken(user);
        subscriptionRepository.save(Subscription.builder()
                .userId(user.getId())
                .planCode(PlanCode.SOLO)
                .status(SubscriptionStatus.ACTIVE)
                .build());
        seedHost(user.getId(), "Poste 1");
        seedHost(user.getId(), "Poste 2");
        seedHost(user.getId(), "Poste 3");
    }

    @Test
    void threeSeatsChangeNothingToTheQuotaUntilThePoConfiguresTheSupplement() throws Exception {
        mockMvc.perform(get("/api/usage").contextPath("/api").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotaTokens").value(SOLO_TOKENS));
    }

    @Test
    void theSeatsAreCountedAndShownButTheSupplementIsNotBilled() throws Exception {
        mockMvc.perform(get("/api/billing/seats").contextPath("/api").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countedSeats").value(3))
                .andExpect(jsonPath("$.extraSeats").value(2))
                .andExpect(jsonPath("$.grantedTokens").value(0))
                .andExpect(jsonPath("$.billed").value(false))
                .andExpect(jsonPath("$.displayPrice").value(""));
    }

    private void seedHost(UUID userId, String name) {
        hostRepository.save(RunnerHost.builder().userId(userId).name(name).build());
    }
}
