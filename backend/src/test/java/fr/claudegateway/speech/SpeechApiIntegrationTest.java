package fr.claudegateway.speech;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

import fr.claudegateway.account.AccountService;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** Paramètres › Vidéos, de bout en bout (F-215 / SF-215-01) : voix, préférence, échantillon, isolation. */
@SpringBootTest(properties = "app.speech.api-key=test-only-key")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SpeechApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private SpeechPreferenceRepository preferenceRepository;
    @Autowired private AccountService accountService;

    @MockBean private SpeechProvider provider;

    private User alice;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
        reset(provider);
        alice = userRepository.save(user("speech-alice-" + UUID.randomUUID() + "@example.com"));
        User bob = userRepository.save(user("speech-bob-" + UUID.randomUUID() + "@example.com"));
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
    }

    private static User user(String email) {
        return User.builder().email(email).emailVerified(true).provider(AuthProvider.LOCAL)
                .role(UserRole.USER).createdAt(OffsetDateTime.now()).build();
    }

    private ResultActions voices(String token) throws Exception {
        return mockMvc.perform(get("/api/speech/voices").contextPath("/api").header("Authorization", "Bearer " + token));
    }

    private ResultActions save(String token, String json) throws Exception {
        return mockMvc.perform(put("/api/speech/preference").contextPath("/api")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    @DisplayName("Défaut D2, enregistrement, relecture ; Bob n'en voit rien ; retour au défaut")
    void defaultsSaveIsolationReset() throws Exception {
        voices(aliceToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.defaultVoice").value("onyx"))
                .andExpect(jsonPath("$.defaultInstructions").value(SpeechVoices.DEFAULT_INSTRUCTIONS))
                .andExpect(jsonPath("$.voices.length()").value(11))
                .andExpect(jsonPath("$.voices[?(@.id=='onyx')].label").exists())
                .andExpect(jsonPath("$.preference").doesNotExist());

        save(aliceToken, "{\"voice\":\"Nova\",\"instructions\":\"  plus dynamique \"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.preference.voice").value("nova"))
                .andExpect(jsonPath("$.preference.instructions").value("plus dynamique"));
        voices(aliceToken).andExpect(jsonPath("$.preference.voice").value("nova"));

        voices(bobToken).andExpect(jsonPath("$.preference").doesNotExist());

        mockMvc.perform(delete("/api/speech/preference").contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());
        voices(aliceToken).andExpect(jsonPath("$.preference").doesNotExist());
    }

    @Test
    @DisplayName("400 lisible : voix inconnue, consignes trop longues ; 401 sans jeton")
    void validation() throws Exception {
        save(aliceToken, "{\"voice\":\"robot\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation_error"));
        save(aliceToken, "{\"voice\":\"onyx\",\"instructions\":\"" + "x".repeat(1001) + "\"}")
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/speech/voices").contextPath("/api")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Échantillon : audio/mpeg, généré une fois par voix ; 404 voix inconnue ; 503 fournisseur indisponible")
    void sampleIsCachedPerVoice() throws Exception {
        when(provider.synthesize(eq(SpeechVoices.SAMPLE_TEXT), eq("sage"), anyString()))
                .thenReturn(new SpeechProvider.SpeechAudio(new byte[] {4, 5, 6}, "audio/mpeg"));

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/api/speech/voices/sage/sample").contextPath("/api")
                            .header("Authorization", "Bearer " + (i == 0 ? aliceToken : bobToken)))
                    .andExpect(status().isOk())
                    .andExpect(content().contentType("audio/mpeg"))
                    .andExpect(content().bytes(new byte[] {4, 5, 6}));
        }
        verify(provider, times(1)).synthesize(eq(SpeechVoices.SAMPLE_TEXT), eq("sage"), anyString());

        mockMvc.perform(get("/api/speech/voices/robot/sample").contextPath("/api")
                .header("Authorization", "Bearer " + aliceToken)).andExpect(status().isNotFound());

        when(provider.synthesize(eq(SpeechVoices.SAMPLE_TEXT), eq("verse"), anyString()))
                .thenThrow(new SpeechProviderUnavailableException("off"));
        mockMvc.perform(get("/api/speech/voices/verse/sample").contextPath("/api")
                .header("Authorization", "Bearer " + aliceToken)).andExpect(status().isServiceUnavailable());
    }

    @Test
    @DisplayName("Suppression du compte : la voix par défaut part avec lui")
    void purgedWithAccount() throws Exception {
        save(aliceToken, "{\"voice\":\"ash\"}").andExpect(status().isOk());
        assertThat(preferenceRepository.existsById(alice.getId())).isTrue();

        accountService.deleteAccount(alice.getId());

        assertThat(preferenceRepository.existsById(alice.getId())).isFalse();
    }
}
