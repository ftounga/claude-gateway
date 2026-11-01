package fr.claudegateway.jarvis.stt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.jarvis.JarvisUsageService;
import fr.claudegateway.stt.stream.StreamingTranscriptionProvider;
import fr.claudegateway.teams.meeting.stt.TranscriptionProviderException;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** L'écoute en flux de bout en bout (F-233 / SF-233-04) : ticket, clôture comptée, isolation, erreurs. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisSttSessionApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JarvisUsageService usage;
    @Autowired private ObjectMapper mapper;

    @MockBean private StreamingTranscriptionProvider provider;

    private User alice;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
        reset(provider);
        when(provider.name()).thenReturn("elevenlabs");
        when(provider.isConfigured()).thenReturn(true);
        when(provider.openStreamingSession(any(), anyDouble())).thenReturn(new StreamingTranscriptionProvider
                .StreamingSession("elevenlabs", "wss://api.elevenlabs.io/v1/speech-to-text/realtime?model_id=m",
                        "single-use", Instant.parse("2030-01-01T00:00:00Z"), 16000));
        alice = userRepository.save(user("jarvis-stt-a-" + UUID.randomUUID() + "@ex.com"));
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(userRepository.save(user("jarvis-stt-b-" + UUID.randomUUID() + "@ex.com")));
    }

    private static User user(String email) {
        return User.builder().email(email).emailVerified(true).provider(AuthProvider.LOCAL)
                .role(UserRole.ADMIN).createdAt(OffsetDateTime.now()).build();
    }

    private ResultActions open(String token) throws Exception {
        return mockMvc.perform(post("/api/jarvis/stt-session").contextPath("/api")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    private ResultActions close(String token, String id, String json) throws Exception {
        return mockMvc.perform(post("/api/jarvis/stt-session/" + id + "/close").contextPath("/api")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    @DisplayName("ticket rendu (URL, jeton, échéance, PCM 16 kHz, 120 s) ; clôture comptée ; autrui : 404")
    void ticketAndClose() throws Exception {
        String body = open(aliceToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("elevenlabs"))
                .andExpect(jsonPath("$.token").value("single-use"))
                .andExpect(jsonPath("$.sampleRate").value(16000))
                .andExpect(jsonPath("$.maxSeconds").value(120))
                .andReturn().getResponse().getContentAsString();
        JsonNode ticket = mapper.readTree(body);
        assertThat(body).doesNotContain("api-key").doesNotContain("xi-api");
        String id = ticket.path("sessionId").asText();

        close(bobToken, id, "{\"seconds\":4}").andExpect(status().isNotFound());
        close(aliceToken, id, "{\"seconds\":4}").andExpect(status().isNoContent());
        close(aliceToken, id, "{\"seconds\":4}").andExpect(status().isNotFound());

        assertThat(usage.lastDays(alice.getId(), 1).get(0).getSttStreamSeconds()).isEqualTo(4L);
    }

    @Test
    @DisplayName("503 non configuré ; 502 fournisseur en échec ; 401 sans jeton")
    void errors() throws Exception {
        when(provider.openStreamingSession(any(), anyDouble()))
                .thenThrow(new TranscriptionProviderException("détail interne du fournisseur"));
        open(aliceToken).andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("stt_stream_failed"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("détail interne"))));
        when(provider.isConfigured()).thenReturn(false);
        open(aliceToken).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").value("stt_stream_unavailable"));
        mockMvc.perform(post("/api/jarvis/stt-session").contextPath("/api")).andExpect(status().isUnauthorized());
    }
}
