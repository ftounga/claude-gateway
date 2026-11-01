package fr.claudegateway.jarvis.audition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.jarvis.JarvisVoiceService;
import fr.claudegateway.speech.SpeechProvider;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * L'audition à l'aveugle (F-233 / SF-233-07) : seule Onyx (OpenAI) est disponible ici (pas de clé ElevenLabs) — la
 * liste est aveugle, l'échantillon est dit par sa voix, le choix devient la voix de Jarvis du compte seulement.
 */
@SpringBootTest(properties = { "app.speech.api-key=test-only-key" })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisAuditionApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JarvisVoiceService voice;
    @Autowired private ObjectMapper mapper;

    @MockBean private SpeechProvider provider;

    private User admin;
    private User other;
    private String adminToken;

    @BeforeEach
    void setUp() {
        reset(provider);
        when(provider.synthesize(anyString(), anyString(), anyString()))
                .thenReturn(new SpeechProvider.SpeechAudio(new byte[] {4, 2}, "audio/mpeg"));
        admin = userRepository.save(user("jarvis-aud-a-" + UUID.randomUUID() + "@ex.com", UserRole.ADMIN));
        other = userRepository.save(user("jarvis-aud-b-" + UUID.randomUUID() + "@ex.com", UserRole.ADMIN));
        adminToken = jwtService.generateToken(admin);
    }

    private static User user(String email, UserRole role) {
        return User.builder().email(email).emailVerified(true).provider(AuthProvider.LOCAL)
                .role(role).createdAt(OffsetDateTime.now()).build();
    }

    private ResultActions call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req,
            String token) throws Exception {
        return mockMvc.perform(req.contextPath("/api").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON));
    }

    /** La lettre sous laquelle Onyx (seule disponible ici) est entendue. */
    private String onyxLabel(JsonNode audition) {
        for (JsonNode c : audition.path("candidates")) {
            if (c.path("available").asBoolean()) {
                return c.path("label").asText();
            }
        }
        throw new AssertionError("aucune voix disponible");
    }

    @Test
    @DisplayName("liste aveugle ; échantillon dit par sa voix ; le choix devient la voix de Jarvis du compte seul")
    void blindListSampleAndChoose() throws Exception {
        String body = call(get("/api/admin/jarvis/audition"), adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.phrases.length()").value(4))
                .andExpect(jsonPath("$.candidates.length()").value(3))
                .andExpect(jsonPath("$.chosen").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("onyx").doesNotContain("George").doesNotContain("elevenlabs")
                .doesNotContain("JBFqnCBsd6RMkjVDRZzb");
        String label = onyxLabel(mapper.readTree(body));

        call(post("/api/admin/jarvis/audition/" + label + "/sample").content("{\"phrase\":1}"), adminToken)
                .andExpect(status().isOk()).andExpect(content().bytes(new byte[] {4, 2}));
        verify(provider).synthesize(eq("Je regarde les journaux du build, une seconde."), eq("onyx"), anyString());

        String unavailable = "ABC".replace(label, "").substring(0, 1);
        call(post("/api/admin/jarvis/audition/" + unavailable + "/sample").content("{\"phrase\":0}"), adminToken)
                .andExpect(status().isServiceUnavailable());
        call(post("/api/admin/jarvis/audition/" + unavailable + "/choose"), adminToken)
                .andExpect(status().isServiceUnavailable());

        call(post("/api/admin/jarvis/audition/" + label + "/choose"), adminToken).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Onyx (OpenAI)"))
                .andExpect(jsonPath("$.provider").value("openai"));
        assertThat(voice.current(admin.getId())).isEqualTo(new JarvisVoiceService.VoiceRef("openai", "onyx"));
        assertThat(voice.current(other.getId())).isEqualTo(voice.current());
        call(get("/api/admin/jarvis/audition"), adminToken).andExpect(jsonPath("$.chosen.name").value("Onyx (OpenAI)"));

        call(delete("/api/admin/jarvis/audition/choice"), adminToken).andExpect(status().isNoContent());
        call(get("/api/admin/jarvis/audition"), adminToken).andExpect(jsonPath("$.chosen").doesNotExist());
    }

    @Test
    @DisplayName("404 lettre inconnue ; 400 phrase hors liste ; 403 non-ADMIN")
    void errors() throws Exception {
        call(post("/api/admin/jarvis/audition/Z/sample").content("{\"phrase\":0}"), adminToken)
                .andExpect(status().isNotFound());
        call(post("/api/admin/jarvis/audition/A/sample").content("{\"phrase\":9}"), adminToken)
                .andExpect(status().isBadRequest());
        String userToken = jwtService.generateToken(userRepository.save(user("jarvis-aud-u-" + UUID.randomUUID()
                + "@ex.com", UserRole.USER)));
        call(get("/api/admin/jarvis/audition"), userToken).andExpect(status().isForbidden());
    }
}
