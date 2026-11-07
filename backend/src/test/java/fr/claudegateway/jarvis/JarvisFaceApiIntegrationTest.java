package fr.claudegateway.jarvis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
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
import fr.claudegateway.speech.AlignedSpeech;
import fr.claudegateway.speech.SpeechProviderException;
import fr.claudegateway.speech.elevenlabs.ElevenLabsSpeechProvider;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-250 / SF-250-01 — la voix alignée de bout en bout : NDJSON si l'écran l'accepte et si la voix sait aligner, mp3
 * sinon ; même comptage ; plafond et isolation inchangés ; {@code GET /api/jarvis/face}.
 */
@SpringBootTest(properties = { "app.jarvis.voice.provider=elevenlabs",
        "app.jarvis.voice.voice=onwK4e9ZLuTAKqWW03F9", "app.jarvis.voice.monthly-budget-eur=1" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisFaceApiIntegrationTest {

    private static final String NDJSON_AND_MP3 = "application/x-ndjson, audio/mpeg";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JarvisUsageDayRepository usageRepository;
    @Autowired private JarvisUsageService usageService;

    @MockBean private ElevenLabsSpeechProvider elevenLabs;

    private User alice;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() throws Exception {
        reset(elevenLabs);
        when(elevenLabs.isConfigured()).thenReturn(true);
        alice = userRepository.save(user("face-alice-" + UUID.randomUUID() + "@example.com"));
        User bob = userRepository.save(user("face-bob-" + UUID.randomUUID() + "@example.com"));
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
        doAnswer(inv -> {
            AlignedSpeech.AlignedSink sink = inv.getArgument(3);
            sink.accept(new AlignedSpeech.AlignedChunk("SUQz", List.of(" ", "O", "u", "i", "."),
                    List.of(0.0, 0.0, 0.1, 0.2, 0.3), List.of(0.0, 0.1, 0.2, 0.3, 0.5)));
            sink.accept(new AlignedSpeech.AlignedChunk("//uQ", null, null, null));
            return 2L;
        }).when(elevenLabs).synthesizeAligned(anyString(), anyString(), anyString(), any());
        doAnswer(inv -> {
            OutputStream out = inv.getArgument(3);
            out.write(new byte[] {5, 5});
            return 2L;
        }).when(elevenLabs).synthesizeStream(anyString(), anyString(), anyString(), any());
    }

    private static User user(String email) {
        return User.builder().email(email).emailVerified(true).provider(AuthProvider.LOCAL)
                .role(UserRole.ADMIN).createdAt(OffsetDateTime.now()).build();
    }

    private ResultActions speak(String token, String accept, String json) throws Exception {
        var request = post("/api/jarvis/speech").contextPath("/api").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json);
        if (accept != null) {
            request = request.header("Accept", accept);
        }
        return mockMvc.perform(request);
    }

    @Test
    @DisplayName("Accept NDJSON + voix alignable : une ligne par morceau {a,c,s,e}, comptée comme le mp3")
    void relaysAlignedNdjson() throws Exception {
        String body = speak(aliceToken, NDJSON_AND_MP3, "{\"text\":\"Oui.\"}")
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/x-ndjson"))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String[] lines = body.split("\n");
        assertThat(lines).hasSize(2);
        JsonNode first = MAPPER.readTree(lines[0]);
        assertThat(first.path("a").asText()).isEqualTo("SUQz");
        assertThat(first.path("c")).hasSize(5);
        assertThat(first.path("s").get(4).asDouble()).isEqualTo(0.3);
        assertThat(first.path("e").get(4).asDouble()).isEqualTo(0.5);
        JsonNode second = MAPPER.readTree(lines[1]);
        assertThat(second.path("a").asText()).isEqualTo("//uQ");
        assertThat(second.has("c")).isFalse();

        verify(elevenLabs).synthesizeAligned(eq("Oui."), eq("onwK4e9ZLuTAKqWW03F9"), anyString(), any());
        verify(elevenLabs, never()).synthesizeStream(anyString(), anyString(), anyString(), any());
        JarvisUsageDay day = usageRepository.findByUserIdAndDay(alice.getId(), usageService.today()).orElseThrow();
        assertThat(day.getCharsSpoken()).isEqualTo(4L);
    }

    @Test
    @DisplayName("sans Accept NDJSON : le mp3 de toujours, aucun appel aligné")
    void mp3WithoutNdjsonAccept() throws Exception {
        speak(aliceToken, "audio/mpeg", "{\"text\":\"Oui.\"}")
                .andExpect(status().isOk())
                .andExpect(content().contentType("audio/mpeg"))
                .andExpect(content().bytes(new byte[] {5, 5}));
        speak(aliceToken, null, "{\"text\":\"Oui.\"}").andExpect(content().contentType("audio/mpeg"));
        verify(elevenLabs, never()).synthesizeAligned(anyString(), anyString(), anyString(), any());
    }

    @Test
    @DisplayName("échec avant le premier morceau : 502 JSON lisible ; plafond atteint : 429, aucun appel ; Bob intact")
    void errorsAndBudget() throws Exception {
        doThrow(new SpeechProviderException("boom")).when(elevenLabs)
                .synthesizeAligned(anyString(), anyString(), anyString(), any());
        speak(aliceToken, NDJSON_AND_MP3, "{\"text\":\"Oui.\"}").andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("speech_failed"));

        reset(elevenLabs);
        when(elevenLabs.isConfigured()).thenReturn(true);
        usageRepository.save(JarvisUsageDay.builder().userId(alice.getId()).day(LocalDate.now().withDayOfMonth(1))
                .speechCostUsd(new BigDecimal("5")).updatedAt(OffsetDateTime.now()).build());
        speak(aliceToken, NDJSON_AND_MP3, "{\"text\":\"Oui.\"}").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("jarvis_voice_budget"));
        verify(elevenLabs, never()).synthesizeAligned(anyString(), anyString(), anyString(), any());

        mockMvc.perform(get("/api/jarvis/face").contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.aligned").value(true));
        mockMvc.perform(get("/api/jarvis/face").contextPath("/api")).andExpect(status().isUnauthorized());
    }
}
