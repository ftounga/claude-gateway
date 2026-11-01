package fr.claudegateway.jarvis;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.speech.SpeechProvider;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** Les clips de réaction (F-233 / SF-233-02, D3) : phrase fixe, synthétisée une fois par voix, comptée une fois. */
@SpringBootTest(properties = { "app.speech.api-key=test-only-key", "app.jarvis.voice.voice=echo" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisClipApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JarvisUsageService usage;

    @MockBean private SpeechProvider provider;

    private User alice;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
        reset(provider);
        alice = userRepository.save(user("jarvis-clip-a-" + UUID.randomUUID() + "@example.com"));
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(userRepository.save(user("jarvis-clip-b-" + UUID.randomUUID()
                + "@example.com")));
        when(provider.synthesize(anyString(), anyString(), anyString()))
                .thenReturn(new SpeechProvider.SpeechAudio(new byte[] {1, 2, 3}, "audio/mpeg"));
    }

    private static User user(String email) {
        return User.builder().email(email).emailVerified(true).provider(AuthProvider.LOCAL)
                .role(UserRole.ADMIN).createdAt(OffsetDateTime.now()).build();
    }

    private ResultActions clip(String token, String name) throws Exception {
        return mockMvc.perform(get("/api/jarvis/clips/" + name).contextPath("/api")
                .header("Authorization", "Bearer " + token));
    }

    @Test
    @DisplayName("le clip est servi en mp3 ; synthétisé une seule fois par voix, quel que soit le compte")
    void servesAndCaches() throws Exception {
        clip(aliceToken, "je-regarde").andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "audio/mpeg"))
                .andExpect(content().bytes(new byte[] {1, 2, 3}));
        clip(bobToken, "je-regarde").andExpect(status().isOk()).andExpect(content().bytes(new byte[] {1, 2, 3}));
        clip(aliceToken, "oui").andExpect(status().isOk());

        verify(provider, times(1)).synthesize(eq("Je regarde."), eq("echo"), anyString());
        verify(provider, times(1)).synthesize(eq("Oui, Monsieur ?"), eq("echo"), anyString());
        org.assertj.core.api.Assertions.assertThat(usage.lastDays(alice.getId(), 1).get(0).getCharsSpoken())
                .isEqualTo("Je regarde.".length() + "Oui, Monsieur ?".length());
    }

    @Test
    @DisplayName("404 clip inconnu (aucun texte libre ne passe) ; 401 sans jeton")
    void errors() throws Exception {
        clip(aliceToken, "dis-bonjour").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
        mockMvc.perform(get("/api/jarvis/clips/oui").contextPath("/api")).andExpect(status().isUnauthorized());
    }
}
