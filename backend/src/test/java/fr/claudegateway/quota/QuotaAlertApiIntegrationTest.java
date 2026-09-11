package fr.claudegateway.quota;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ProviderFileReference;
import fr.claudegateway.ai.ProviderFileUpload;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration de l'alerte de consommation (F-42 / SF-42-01) sur {@code /api/usage/alert} :
 * franchissement du seuil par de vrais appels de chat, <b>unicité de l'émission</b>, écartement, et
 * isolation {@code user_id}.
 *
 * <p>Quota d'essai abaissé à 100 tokens et fournisseur stubé à 20 tokens/appel : le seuil de 80 %
 * tombe donc exactement au 4<sup>e</sup> appel, et la limite au 6<sup>e</sup>.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.quota.trial-tokens=100",
        "app.quota.alert.threshold=0.8",
        "app.quota.alert.top-up-pack=STANDARD"})
class QuotaAlertApiIntegrationTest {

    /** Stub renvoyant une consommation fixe de 12 + 8 = 20 tokens par appel. */
    static class StubAIProvider implements AIProvider {
        @Override
        public ChatCompletionResult complete(ChatCompletionRequest request) {
            // 16 tokens d'entrée et 4 de sortie : 20 tokens traités, et — au ratio 4:1, point
            // d'équilibre exact du décompte au coût réel (F-63) — 20 tokens facturés.
            // (16×5 + 4×25) ÷ 9 = 20. Les chiffres de ce test gardent donc leur sens.
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
    private JwtService jwtService;

    private User alice;
    private String aliceToken;
    private User bob;
    private String bobToken;

    @BeforeEach
    void setUp() {
        usageCounterRepository.deleteAll();
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

    private void chat(String token) throws Exception {
        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Bonjour\"}"))
                .andExpect(status().isOk());
    }

    private OffsetDateTime raisedAt(UUID userId) {
        LocalDate periodStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
        return usageCounterRepository.findByUserIdAndPeriodStart(userId, periodStart)
                .map(UsageCounter::getQuotaAlertRaisedAt)
                .orElse(null);
    }

    // ---------- Sécurité ----------

    @Test
    void alertRejectsUnauthenticated() throws Exception {
        mockMvc.perform(get("/api/usage/alert").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void dismissRejectsUnauthenticated() throws Exception {
        mockMvc.perform(post("/api/usage/alert/dismiss").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    // ---------- Franchissement du seuil ----------

    @Test
    void noAlertBeforeTheThresholdIsCrossed() throws Exception {
        chat(aliceToken); // 20 / 100 = 20 %

        mockMvc.perform(get("/api/usage/alert").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.raised", is(false)))
                .andExpect(jsonPath("$.usedTokens", is(20)))
                .andExpect(jsonPath("$.quotaTokens", is(100)))
                .andExpect(jsonPath("$.usedPercent", is(20)))
                .andExpect(jsonPath("$.thresholdPercent", is(80)))
                .andExpect(jsonPath("$.periodEnd", notNullValue()))
                .andExpect(jsonPath("$.topUp", nullValue()));

        assertThat(raisedAt(alice.getId())).isNull();
    }

    @Test
    void alertIsRaisedWithFiguresAndOneClickPackWhenThresholdIsCrossed() throws Exception {
        for (int call = 0; call < 4; call++) {
            chat(aliceToken); // 80 / 100 = 80 % → seuil atteint
        }

        mockMvc.perform(get("/api/usage/alert").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.raised", is(true)))
                .andExpect(jsonPath("$.usedTokens", is(80)))
                .andExpect(jsonPath("$.quotaTokens", is(100)))
                .andExpect(jsonPath("$.remainingTokens", is(20)))
                .andExpect(jsonPath("$.usedPercent", is(80)))
                .andExpect(jsonPath("$.thresholdPercent", is(80)))
                .andExpect(jsonPath("$.topUp.code", is("STANDARD")))
                .andExpect(jsonPath("$.topUp.tokens", is(1000000)))
                // F-67 : le pack porte désormais son montant d'affichage. Celui du pack 1 M n'est
                // pas configuré (il appartient au PO) — l'API renvoie donc null, et surtout PAS un
                // montant de repli : c'est à l'écran de dire que le prix sera indiqué au paiement.
                .andExpect(jsonPath("$.topUp.priceEur", nullValue()));
    }

    /**
     * Le test central de la subfeature : l'alerte est émise <b>une seule fois</b> par période. Les
     * appels qui suivent le franchissement ne réécrivent pas l'horodatage — sans quoi un tour
     * d'agent en produirait des dizaines et l'utilisateur cesserait de les lire.
     */
    @Test
    void alertIsRaisedOnlyOncePerPeriod() throws Exception {
        for (int call = 0; call < 4; call++) {
            chat(aliceToken);
        }
        OffsetDateTime firstRaise = raisedAt(alice.getId());
        assertThat(firstRaise).isNotNull();

        chat(aliceToken); // 100 / 100 : encore au-dessus du seuil

        assertThat(raisedAt(alice.getId())).isEqualTo(firstRaise);
    }

    // ---------- Écartement ----------

    @Test
    void dismissedAlertNeverComesBackWithinThePeriod() throws Exception {
        for (int call = 0; call < 4; call++) {
            chat(aliceToken);
        }

        mockMvc.perform(post("/api/usage/alert/dismiss").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/usage/alert").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.raised", is(false)));

        // La consommation continue de monter : l'alerte ne revient pas pour autant.
        chat(aliceToken);
        mockMvc.perform(get("/api/usage/alert").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.raised", is(false)))
                .andExpect(jsonPath("$.usedTokens", is(100)));
    }

    @Test
    void dismissIsHarmlessWhenNoAlertIsRaised() throws Exception {
        mockMvc.perform(post("/api/usage/alert/dismiss").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        assertThat(usageCounterRepository.findAll()).isEmpty();
    }

    // ---------- Non-régression F-10 ----------

    @Test
    void blockingAtTheLimitIsUnchanged() throws Exception {
        for (int call = 0; call < 5; call++) {
            chat(aliceToken); // 100 / 100
        }

        mockMvc.perform(post("/api/chat").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Encore\"}"))
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.error", is("quota_exceeded")));
    }

    // ---------- Isolation utilisateur ----------

    @Test
    void alertOfOneUserIsNeverVisibleToAnother() throws Exception {
        for (int call = 0; call < 4; call++) {
            chat(aliceToken);
        }
        chat(bobToken); // Bob : 20 / 100

        mockMvc.perform(get("/api/usage/alert").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(jsonPath("$.raised", is(false)))
                .andExpect(jsonPath("$.usedTokens", is(20)));

        mockMvc.perform(get("/api/usage/alert").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.raised", is(true)));
    }

    @Test
    void dismissOfOneUserDoesNotClearAnotherUsersAlert() throws Exception {
        for (int call = 0; call < 4; call++) {
            chat(aliceToken);
        }
        chat(bobToken);

        mockMvc.perform(post("/api/usage/alert/dismiss").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/usage/alert").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.raised", is(true)));
    }
}
