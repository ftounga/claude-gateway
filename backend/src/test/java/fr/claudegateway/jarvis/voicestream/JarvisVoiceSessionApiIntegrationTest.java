package fr.claudegateway.jarvis.voicestream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-257 / SF-257-02 — le ticket de session vocale : émission (coupe-circuit ouvert), sujet vérifié sous {@code user_id},
 * description au service, portée fermée, logout-all, non-régression de la session ordinaire.
 */
@SpringBootTest(properties = "app.jarvis.voice-stream.enabled=true")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisVoiceSessionApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private JwtService jwtService;
    @Autowired private ObjectMapper objectMapper;

    private User owner;
    private User other;
    private String ownerToken;

    @BeforeEach
    void setUp() {
        owner = user("voice-a-" + UUID.randomUUID() + "@ex.com");
        other = user("voice-b-" + UUID.randomUUID() + "@ex.com");
        ownerToken = jwtService.generateToken(owner);
    }

    private User user(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true).provider(AuthProvider.LOCAL)
                .role(UserRole.ADMIN).createdAt(OffsetDateTime.now()).build());
    }

    private ResultActions issue(String token, String body) throws Exception {
        return mockMvc.perform(post("/api/jarvis/voice-session").contextPath("/api")
                .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private String ticket(String body) throws Exception {
        String json = issue(ownerToken, body).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        JsonNode node = objectMapper.readTree(json);
        assertThat(node.path("url").asText()).isEqualTo("/voice/ws");
        assertThat(node.path("sampleRate").asInt()).isEqualTo(16000);
        assertThat(node.path("expiresAt").asText()).isNotBlank();
        return node.path("ticket").asText();
    }

    private ResultActions me(String token) throws Exception {
        return mockMvc.perform(get("/api/jarvis/voice-session/me").contextPath("/api")
                .header("Authorization", "Bearer " + token));
    }

    @Test
    @DisplayName("un ticket émis décrit l'utilisateur et son sujet vérifié ; un sujet d'autrui est refusé")
    void issuesAndDescribes() throws Exception {
        Workspace mine = workspaceRepository.save(Workspace.builder().userId(owner.getId()).name("voice-mine").build());
        Workspace theirs = workspaceRepository.save(Workspace.builder().userId(other.getId()).name("voice-b").build());

        String ticket = ticket("{\"workspaceId\":\"" + mine.getId() + "\"}");
        me(ticket).andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(owner.getId().toString()))
                .andExpect(jsonPath("$.workspaceId").value(mine.getId().toString()))
                .andExpect(jsonPath("$.expiresAt").isNotEmpty());

        me(ticket("{}")).andExpect(status().isOk()).andExpect(jsonPath("$.workspaceId").doesNotExist());

        issue(ownerToken, "{\"workspaceId\":\"" + theirs.getId() + "\"}").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("workspace_not_found"));
        issue(ownerToken, "{\"workspaceId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("sans session : 401 ; /me sous une session ordinaire : 403 voice_ticket_required")
    void requiresAuthAndTicket() throws Exception {
        mockMvc.perform(post("/api/jarvis/voice-session").contextPath("/api")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        me(ownerToken).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("voice_ticket_required"));
    }

    @Test
    @DisplayName("la portée jarvis-voice ouvre sa liste fermée et rien d'autre")
    void scopeIsClosed() throws Exception {
        String ticket = ticket("{}");
        mockMvc.perform(post("/api/jarvis/timings").contextPath("/api").header("Authorization", "Bearer " + ticket)
                .contentType(MediaType.APPLICATION_JSON).content("{\"loop\":\"stream\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/workspaces").contextPath("/api").header("Authorization", "Bearer " + ticket))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("jarvis_voice_scope"));
        mockMvc.perform(get("/api/admin/jarvis/latency").contextPath("/api")
                .header("Authorization", "Bearer " + ticket)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("jarvis_voice_scope"));
        // Un ticket n'en émet pas d'autre.
        issue(ticket, "{}").andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("jarvis_voice_scope"));
        // La session ordinaire, elle, n'est pas bornée.
        mockMvc.perform(get("/api/workspaces").contextPath("/api").header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("logout-all : un ticket émis avant n'ouvre plus rien")
    void logoutAllRevokesTicket() throws Exception {
        String ticket = ticket("{}");
        owner.setTokenVersion(owner.getTokenVersion() + 1);
        userRepository.save(owner);
        me(ticket).andExpect(status().isUnauthorized());
    }
}
