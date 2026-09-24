package fr.claudegateway.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Les <b>abonnements Web Push</b>, de bout en bout (F-153 / SF-153-02).
 *
 * <p>Trois questions : s'abonne-t-on, se désabonne-t-on, et — la plus importante — <b>peut-on
 * toucher l'abonnement d'un autre</b> ? L'identité vient du jeton, jamais du corps : un compte ne
 * voit ni ne retire les appareils d'un autre.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PushSubscriptionApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PushSubscriptionRepository pushSubscriptionRepository;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private UUID aliceId;
    private String bobToken;
    private UUID bobId;

    @BeforeEach
    void setUp() {
        pushSubscriptionRepository.deleteAll();
        userRepository.deleteAll();

        User alice = seedUser("alice-push@example.com");
        aliceId = alice.getId();
        aliceToken = jwtService.generateToken(alice);

        User bob = seedUser("bob-push@example.com");
        bobId = bob.getId();
        bobToken = jwtService.generateToken(bob);
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }

    private String subscribeBody(String endpoint) {
        return "{\"endpoint\":\"" + endpoint + "\",\"keys\":{\"p256dh\":\"pub-key\",\"auth\":\"auth-secret\"}}";
    }

    private void subscribe(String token, String endpoint) throws Exception {
        mockMvc.perform(post("/api/push/subscriptions").contextPath("/api")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(subscribeBody(endpoint)))
                .andExpect(status().isCreated());
    }

    // ------------------------------------------------------------------ cas nominal

    @Test
    void subscribingRegistersTheDeviceForTheOwner() throws Exception {
        subscribe(aliceToken, "https://push.example/alice-1");

        assertThat(pushSubscriptionRepository.findByUserId(aliceId)).hasSize(1);
        assertThat(pushSubscriptionRepository.findByUserId(aliceId).get(0).getEndpoint())
                .isEqualTo("https://push.example/alice-1");
    }

    @Test
    void subscribingTwiceWithTheSameEndpointDoesNotDuplicate() throws Exception {
        subscribe(aliceToken, "https://push.example/alice-1");
        subscribe(aliceToken, "https://push.example/alice-1");

        assertThat(pushSubscriptionRepository.findByUserId(aliceId)).hasSize(1);
    }

    @Test
    void unsubscribingRemovesTheDevice() throws Exception {
        subscribe(aliceToken, "https://push.example/alice-1");

        mockMvc.perform(delete("/api/push/subscriptions").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"https://push.example/alice-1\"}"))
                .andExpect(status().isNoContent());

        assertThat(pushSubscriptionRepository.findByUserId(aliceId)).isEmpty();
    }

    @Test
    void unsubscribingAnUnknownEndpointIsNotAnError() throws Exception {
        mockMvc.perform(delete("/api/push/subscriptions").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"https://push.example/never\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void theVapidPublicKeyIsServedButNullWhenPushIsNotConfigured() throws Exception {
        // En profil test, aucune clé VAPID n'est fournie : la publique est null, la privée n'est
        // JAMAIS exposée (elle n'a pas de champ dans la réponse).
        mockMvc.perform(get("/api/push/vapid-public-key").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").doesNotExist());
    }

    // ------------------------------------------------------------------ validation & accès

    @Test
    void anEmptyEndpointIsRefused() throws Exception {
        mockMvc.perform(post("/api/push/subscriptions").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON).content(subscribeBody("")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingKeysAreRefused() throws Exception {
        mockMvc.perform(post("/api/push/subscriptions").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"https://push.example/alice-1\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void theEndpointIsNotReachableWithoutAToken() throws Exception {
        mockMvc.perform(get("/api/push/vapid-public-key").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ isolation user_id

    @Test
    void oneUserNeverSeesAnothersSubscriptions() throws Exception {
        subscribe(bobToken, "https://push.example/bob-1");

        assertThat(pushSubscriptionRepository.findByUserId(aliceId)).isEmpty();
        assertThat(pushSubscriptionRepository.findByUserId(bobId)).hasSize(1);
    }

    @Test
    void oneUserCannotRemoveAnothersSubscription() throws Exception {
        subscribe(bobToken, "https://push.example/bob-1");

        // Alice tente de retirer l'endpoint de Bob : borné à SON user_id, sans effet sur Bob.
        mockMvc.perform(delete("/api/push/subscriptions").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"endpoint\":\"https://push.example/bob-1\"}"))
                .andExpect(status().isNoContent());

        assertThat(pushSubscriptionRepository.findByUserId(bobId)).hasSize(1);
    }

    @Test
    void theSameEndpointCanBelongToTwoUsersIndependently() throws Exception {
        // La contrainte d'unicité est (user_id, endpoint) : deux comptes peuvent porter le même
        // endpoint sans se heurter, et se désabonner sans toucher l'autre.
        subscribe(aliceToken, "https://push.example/shared");
        subscribe(bobToken, "https://push.example/shared");

        assertThat(pushSubscriptionRepository.findByUserId(aliceId)).hasSize(1);
        assertThat(pushSubscriptionRepository.findByUserId(bobId)).hasSize(1);
    }
}
