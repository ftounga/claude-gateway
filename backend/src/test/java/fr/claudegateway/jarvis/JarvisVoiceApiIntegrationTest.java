package fr.claudegateway.jarvis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.speech.SpeechProvider;
import fr.claudegateway.speech.SpeechProviderException;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** La voix de Jarvis de bout en bout (F-231 / SF-231-01) : flux audio, erreurs, plafond, cumul, isolation. */
@SpringBootTest(properties = { "app.speech.api-key=test-only-key", "app.jarvis.voice.monthly-budget-eur=1" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisVoiceApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JarvisUsageDayRepository usageRepository;
    @Autowired private JarvisUsageService usageService;

    @MockBean private SpeechProvider provider;

    private User alice;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() throws Exception {
        reset(provider);
        alice = userRepository.save(user("jarvis-alice-" + UUID.randomUUID() + "@example.com"));
        User bob = userRepository.save(user("jarvis-bob-" + UUID.randomUUID() + "@example.com"));
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
        doAnswer(inv -> {
            OutputStream out = inv.getArgument(3);
            out.write(new byte[] {7, 7, 7});
            return 3L;
        }).when(provider).synthesizeStream(anyString(), anyString(), anyString(), any());
    }

    /** ADMIN : la Forge est ouverte d'office (le droit d'accès n'est pas l'objet de ce test). */
    private static User user(String email) {
        return User.builder().email(email).emailVerified(true).provider(AuthProvider.LOCAL)
                .role(UserRole.ADMIN).createdAt(OffsetDateTime.now()).build();
    }

    private ResultActions speak(String token, String json) throws Exception {
        return mockMvc.perform(post("/api/jarvis/speech").contextPath("/api")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    @DisplayName("200 audio/mpeg avec la voix onyx ; la lecture est comptée dans la journée d'Alice, pas de Bob")
    void speaksAndCounts() throws Exception {
        speak(aliceToken, "{\"text\":\"C'est fait.\"}")
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/mpeg"))
                .andExpect(content().bytes(new byte[] {7, 7, 7}));

        org.mockito.Mockito.verify(provider).synthesizeStream(eq("C'est fait."), eq("onyx"), anyString(), any());
        JarvisUsageDay day = usageRepository.findByUserIdAndDay(alice.getId(), usageService.today()).orElseThrow();
        assertThat(day.getCharsSpoken()).isEqualTo(11L);
        assertThat(day.getSpeechCostUsd()).isPositive();

        mockMvc.perform(get("/api/jarvis/voice").contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voice").value("onyx"))
                .andExpect(jsonPath("$.monthSpentEur").value(0.0))
                .andExpect(jsonPath("$.budgetReached").value(false));
    }

    @Test
    @DisplayName("400 texte vide, 401 sans jeton, 502 fournisseur en échec avant le premier octet")
    void errors() throws Exception {
        speak(aliceToken, "{\"text\":\"  \"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_error"));
        mockMvc.perform(post("/api/jarvis/speech").contextPath("/api").contentType(MediaType.APPLICATION_JSON)
                .content("{\"text\":\"x\"}")).andExpect(status().isUnauthorized());

        doThrow(new SpeechProviderException("boom")).when(provider)
                .synthesizeStream(anyString(), anyString(), anyString(), any());
        speak(aliceToken, "{\"text\":\"Bonjour.\"}").andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("speech_failed"));
    }

    @Test
    @DisplayName("plafond du mois atteint (1 €) : 429 jarvis_voice_budget, aucun appel au fournisseur")
    void budget() throws Exception {
        usageRepository.save(JarvisUsageDay.builder().userId(alice.getId()).day(LocalDate.now().withDayOfMonth(1))
                .speechCostUsd(new BigDecimal("5")).updatedAt(OffsetDateTime.now()).build());

        speak(aliceToken, "{\"text\":\"Bonjour.\"}").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("jarvis_voice_budget"));
        org.mockito.Mockito.verify(provider, org.mockito.Mockito.never())
                .synthesizeStream(anyString(), anyString(), anyString(), any());

        mockMvc.perform(get("/api/jarvis/voice").contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(jsonPath("$.budgetReached").value(true));
        // Bob n'hérite pas du plafond d'Alice.
        speak(bobToken, "{\"text\":\"Bonjour.\"}").andExpect(status().isOk());
    }
}
