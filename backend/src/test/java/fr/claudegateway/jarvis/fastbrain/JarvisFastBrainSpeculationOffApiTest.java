package fr.claudegateway.jarvis.fastbrain;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** F-252 / SF-252-02 — coupe-circuit : une réponse provisoire est refusée (409), l'écran garde la retranscription d'abord. */
@SpringBootTest(properties = {"app.jarvis.fast-brain.enabled=true", "app.jarvis.fast-brain.speculation=false"})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisFastBrainSpeculationOffApiTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @MockitoBean private AiAgentProvider agentProvider;

    @Test
    @DisplayName("coupe-circuit : 409 avec un speculationId, aucun appel au modèle")
    void refusedWhenOff() throws Exception {
        User alice = userRepository.save(User.builder().email("spec-off-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.ADMIN)
                .createdAt(OffsetDateTime.now()).build());
        mockMvc.perform(post("/api/jarvis/converse").contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Bonjour\",\"speculationId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isConflict());
        org.mockito.Mockito.verifyNoInteractions(agentProvider);
    }
}
