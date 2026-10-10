package fr.claudegateway.notifications;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
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

/** Les préférences de notification, de bout en bout (F-185 / SF-185-06). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class NotificationPreferencesApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
        User alice = userRepository.save(user("prefs-alice-" + UUID.randomUUID() + "@example.com"));
        User bob = userRepository.save(user("prefs-bob-" + UUID.randomUUID() + "@example.com"));
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
    }

    private static User user(String email) {
        return User.builder().email(email).emailVerified(true).provider(AuthProvider.LOCAL)
                .role(UserRole.USER).createdAt(OffsetDateTime.now()).build();
    }

    private org.springframework.test.web.servlet.ResultActions save(String token, String json) throws Exception {
        return mockMvc.perform(put("/api/notifications/preferences").contextPath("/api")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    void defaultsThenSaveThenRead() throws Exception {
        mockMvc.perform(get("/api/notifications/preferences").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mutedEvents.length()").value(0))
                .andExpect(jsonPath("$.quietFrom").doesNotExist())
                .andExpect(jsonPath("$.timeZone").value("Europe/Paris"))
                .andExpect(jsonPath("$.events[?(@.code=='QUESTION_ASKED')].critical").value(true))
                .andExpect(jsonPath("$.events[?(@.code=='TURN_DONE')].critical").value(false));

        save(aliceToken, "{\"mutedEvents\":[\"TURN_DONE\"],\"quietFrom\":\"22:00\",\"quietTo\":\"07:00\","
                + "\"timeZone\":\"America/Montreal\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mutedEvents[0]").value("TURN_DONE"));

        mockMvc.perform(get("/api/notifications/preferences").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.mutedEvents[0]").value("TURN_DONE"))
                .andExpect(jsonPath("$.quietFrom").value("22:00"))
                .andExpect(jsonPath("$.quietTo").value("07:00"))
                .andExpect(jsonPath("$.timeZone").value("America/Montreal"));

        // Bob n'en voit rien : ses préférences restent celles par défaut.
        mockMvc.perform(get("/api/notifications/preferences").contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(jsonPath("$.mutedEvents.length()").value(0))
                .andExpect(jsonPath("$.timeZone").value("Europe/Paris"));
    }

    @Test
    void invalidRequestsAreRejectedReadably() throws Exception {
        save(aliceToken, "{\"mutedEvents\":[\"NOPE\"]}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_error"));
        save(aliceToken, "{\"mutedEvents\":[\"AUTHORIZATION_REQUESTED\"]}").andExpect(status().isBadRequest());
        save(aliceToken, "{\"quietFrom\":\"25:00\",\"quietTo\":\"07:00\"}").andExpect(status().isBadRequest());
        save(aliceToken, "{\"quietFrom\":\"22:00\"}").andExpect(status().isBadRequest());
        save(aliceToken, "{\"quietFrom\":\"08:00\",\"quietTo\":\"08:00\"}").andExpect(status().isBadRequest());
        save(aliceToken, "{\"timeZone\":\"Mars/Olympus\"}").andExpect(status().isBadRequest());
    }

    @Test
    void withoutATokenItIsRefused() throws Exception {
        mockMvc.perform(get("/api/notifications/preferences").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }
}
