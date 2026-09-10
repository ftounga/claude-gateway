package fr.claudegateway.access;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.jayway.jsonpath.JsonPath;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration des codes d'accès à durée limitée (F-62 / SF-62-01).
 *
 * <p>Trois propriétés valent d'être vues de bout en bout, parce qu'elles sont exactement les
 * promesses de F-62 : le code est réservé à l'ADMIN à l'émission ; il ne sert <b>qu'une fois</b> ;
 * et sa consommation <b>ne change rien</b> à l'abonnement — ce dernier point est vérifié en
 * comparant, à l'octet près, la réponse de {@code /billing/subscription} avant et après.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccessCodeApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private AccessCodeRepository accessCodeRepository;
    @Autowired private JwtService jwtService;

    private String adminToken;
    private User alice;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
        accessCodeRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        User admin = userRepository.save(User.builder().email("admin@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        adminToken = jwtService.generateToken(admin);

        alice = userRepository.save(User.builder().email("alice@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);

        User bob = userRepository.save(User.builder().email("bob@example.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        bobToken = jwtService.generateToken(bob);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    /** Émet un code via l'API admin et renvoie le clair (la seule fois où il est lisible). */
    private String issueCode(String label, String assignedEmail) throws Exception {
        String body = assignedEmail == null
                ? "{\"label\":\"" + label + "\"}"
                : "{\"label\":\"" + label + "\",\"assignedEmail\":\"" + assignedEmail + "\"}";
        String json = mockMvc.perform(post("/api/admin/access-codes").contextPath("/api")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.code");
    }

    private void redeem(String token, String code, int expectedStatus, String expectedError)
            throws Exception {
        var result = mockMvc.perform(post("/api/access-code/redeem").contextPath("/api")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().is(expectedStatus));
        if (expectedError != null) {
            result.andExpect(jsonPath("$.error", is(expectedError)));
        }
    }

    // ------------------------------------------------------------------- émission

    @Test
    @DisplayName("Seul un ADMIN émet un code ; un utilisateur est refusé, un anonyme rejeté")
    void issuingIsReservedToAdmins() throws Exception {
        mockMvc.perform(post("/api/admin/access-codes").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"démo\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/admin/access-codes").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"démo\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/admin/access-codes").contextPath("/api")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"démo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", notNullValue()))
                .andExpect(jsonPath("$.view.state", is("ISSUED")))
                .andExpect(jsonPath("$.view.grantedPlanCode", is("GOLD")))
                .andExpect(jsonPath("$.view.durationHours", is(24)));
    }

    @Test
    @DisplayName("Un libellé vide est refusé (400)")
    void blankLabelIsRejected() throws Exception {
        mockMvc.perform(post("/api/admin/access-codes").contextPath("/api")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"label\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }

    @Test
    @DisplayName("La liste admin porte la trace, jamais le code en clair")
    void adminListNeverLeaksTheClearCode() throws Exception {
        String code = issueCode("démo prospect", null);
        redeem(aliceToken, code, 200, null);

        mockMvc.perform(get("/api/admin/access-codes").contextPath("/api")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].label", is("démo prospect")))
                .andExpect(jsonPath("$[0].state", is("ACTIVE")))
                .andExpect(jsonPath("$[0].redeemedByEmail", is("alice@example.com")))
                .andExpect(jsonPath("$[0].redeemedAt", notNullValue()))
                .andExpect(jsonPath("$[0].grantedUntil", notNullValue()))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(code))));

        mockMvc.perform(get("/api/admin/access-codes").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------- consommation

    @Test
    @DisplayName("Un code ouvre le droit une fois — le second essai est refusé")
    void aCodeWorksExactlyOnce() throws Exception {
        String code = issueCode("démo", null);

        mockMvc.perform(post("/api/access-code/redeem").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code.toLowerCase() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(true)))
                .andExpect(jsonPath("$.grantedPlanCode", is("GOLD")))
                .andExpect(jsonPath("$.grantedUntil", notNullValue()));

        // Le même code, un autre compte : il a déjà servi.
        redeem(bobToken, code, 409, "access_code_used");
    }

    @Test
    @DisplayName("Consommer un code ne change RIEN à l'abonnement")
    void redeemingChangesNothingInTheSubscription() throws Exception {
        subscriptionRepository.save(Subscription.builder()
                .userId(alice.getId()).planCode(PlanCode.SOLO)
                .status(SubscriptionStatus.ACTIVE).build());

        String before = mockMvc.perform(get("/api/billing/subscription").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        redeem(aliceToken, issueCode("démo", null), 200, null);

        String after = mockMvc.perform(get("/api/billing/subscription").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // À l'octet près : ni le plan, ni le statut, ni la période n'ont bougé. Un code n'est pas un
        // paiement — il ne doit rien changer à ce que le client paie.
        assertThat(after).isEqualTo(before);
        Subscription stored = subscriptionRepository.findByUserId(alice.getId()).orElseThrow();
        assertThat(stored.getPlanCode()).isEqualTo(PlanCode.SOLO);
        assertThat(stored.getStripeSubscriptionId()).isNull();
    }

    @Test
    @DisplayName("Le droit ouvre la Forge à un compte qui n'y avait pas accès")
    void theGrantOpensTheForge() throws Exception {
        // Avant : Alice est en essai, sans plan — la Forge lui est fermée.
        mockMvc.perform(get("/api/workspaces").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isForbidden());

        redeem(aliceToken, issueCode("démo", null), 200, null);

        mockMvc.perform(get("/api/workspaces").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Code inconnu → 404 ; corps sans code → 400")
    void unknownAndMalformedCodesAreRefused() throws Exception {
        redeem(aliceToken, "FORGE-ZZZZ-ZZZZ", 404, "access_code_invalid");

        mockMvc.perform(post("/api/access-code/redeem").contextPath("/api")
                        .header("Authorization", bearer(aliceToken))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }

    @Test
    @DisplayName("Un code nominatif n'ouvre rien à un autre compte")
    void anAssignedCodeRefusesOtherAccounts() throws Exception {
        String code = issueCode("démo nominative", "alice@example.com");

        redeem(bobToken, code, 403, "access_code_not_for_account");
        redeem(aliceToken, code, 200, null);
    }

    @Test
    @DisplayName("Pas de cumul : un second code est refusé tant qu'un droit est en cours")
    void aSecondCodeIsRefusedWhileAGrantIsLive() throws Exception {
        redeem(aliceToken, issueCode("premier", null), 200, null);
        redeem(aliceToken, issueCode("second", null), 409, "access_code_already_granted");
    }

    // ------------------------------------------------------------------- lecture du droit

    @Test
    @DisplayName("Isolation : chacun ne voit que son propre droit")
    void eachUserOnlySeesTheirOwnGrant() throws Exception {
        redeem(aliceToken, issueCode("démo", null), 200, null);

        mockMvc.perform(get("/api/access-code/grant").contextPath("/api")
                        .header("Authorization", bearer(aliceToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(true)))
                .andExpect(jsonPath("$.label", is("démo")));

        // Bob n'a rien consommé : il ne voit pas le droit d'Alice. 200 et non 404 — l'absence de
        // droit est un état normal, pas une ressource introuvable.
        mockMvc.perform(get("/api/access-code/grant").contextPath("/api")
                        .header("Authorization", bearer(bobToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(false)))
                .andExpect(jsonPath("$.grantedUntil", nullValue()));

        mockMvc.perform(get("/api/access-code/grant").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }
}
