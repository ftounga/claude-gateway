package fr.claudegateway.jarvis.companion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-232 / SF-232-06 (D8) — <b>téléphone verrouillé, aucune autorisation d'outil ne part</b> : la session du compagnon
 * verrouillée reçoit 423 {@code jarvis_phone_locked} pour « autoriser » ; « refuser » passe ; une session déverrouillée
 * ou ordinaire suit le chemin normal (409 : rien en attente ici).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisCompanionLockedGateIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private JwtService jwtService;

    private String aliceJwt;
    private Workspace subject;

    @BeforeEach
    void setUp() {
        User alice = userRepository.save(User.builder().email("gate-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        aliceJwt = jwtService.generateToken(alice);
        subject = workspaceRepository.save(Workspace.builder().userId(alice.getId()).name("Sujet")
                .projectPath("sujet").executionTarget(WorkspaceExecutionTarget.RUNNER).build());
    }

    @Test
    @DisplayName("verrouillé : « autoriser » est refusé (423), « refuser » passe")
    void lockedAllowIsRefused() throws Exception {
        String locked = companionSession(true);
        confirm(locked, "allow").andExpect(status().isLocked());
        confirm(locked, "deny").andExpect(r -> assertThat(r.getResponse().getStatus()).isNotEqualTo(423));
    }

    @Test
    @DisplayName("déverrouillé ou session ordinaire : chemin normal, jamais 423")
    void unlockedFollowsTheNormalPath() throws Exception {
        confirm(companionSession(false), "allow")
                .andExpect(r -> assertThat(r.getResponse().getStatus()).isNotEqualTo(423));
        confirm(aliceJwt, "allow").andExpect(r -> assertThat(r.getResponse().getStatus()).isNotEqualTo(423));
    }

    private ResultActions confirm(String jwt, String decision) throws Exception {
        return as(jwt, post("/api/workspaces/" + subject.getId() + "/chat/confirm")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"toolUseId\":\"t-1\",\"decision\":\"" + decision + "\"}"));
    }

    private String companionSession(boolean locked) throws Exception {
        String code = json.readTree(as(aliceJwt, post("/api/jarvis/companions/pairing-code"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("code").asText();
        String token = json.readTree(mockMvc.perform(post("/api/runner/jarvis/pair").contextPath("/api")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
        return json.readTree(mockMvc.perform(post("/api/runner/jarvis/session").contextPath("/api")
                        .header(JarvisCompanionDeviceController.TOKEN_HEADER, token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"locked\":" + locked + "}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("token").asText();
    }

    private ResultActions as(String jwt, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.contextPath("/api").header("Authorization", "Bearer " + jwt));
    }
}
