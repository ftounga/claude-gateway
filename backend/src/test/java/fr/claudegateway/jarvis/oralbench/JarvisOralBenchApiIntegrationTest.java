package fr.claudegateway.jarvis.oralbench;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** Le banc de la parole (F-240 / SF-240-02) : ADMIN seulement, sujet du compte, rapport sous user_id. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisOralBenchApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private JarvisOralBenchRunRepository runs;
    @Autowired private JarvisOralBenchService service;
    @Autowired private JwtService jwtService;

    private User user(UserRole role) {
        return userRepository.save(User.builder().email("oral-bench-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(role).createdAt(OffsetDateTime.now()).build());
    }

    @Test
    @DisplayName("403 hors ADMIN ; 400 sans sujet ; 400 sur le sujet d'un autre compte ; 404 pour un passage inconnu")
    void accessAndValidation() throws Exception {
        User admin = user(UserRole.ADMIN);
        User other = user(UserRole.ADMIN);
        String userToken = jwtService.generateToken(user(UserRole.USER));
        String adminToken = jwtService.generateToken(admin);
        mockMvc.perform(post("/api/admin/jarvis/oral-bench").contextPath("/api")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/jarvis/oral-bench").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("validation_error"));
        Workspace theirs = workspaceRepository.save(Workspace.builder().userId(other.getId()).name("ob-b").build());
        mockMvc.perform(post("/api/admin/jarvis/oral-bench").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workspaceId\":\"" + theirs.getId() + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/admin/jarvis/oral-bench/" + UUID.randomUUID()).contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("un rapport rangé en base se relit sous user_id seulement")
    void storedReportIsReadUnderUserId() throws Exception {
        User admin = user(UserRole.ADMIN);
        User other = user(UserRole.ADMIN);
        UUID runId = UUID.randomUUID();
        runs.save(JarvisOralBenchRun.builder().id(runId).userId(admin.getId()).startedAt(OffsetDateTime.now())
                .status("DONE").reportJson("{\"runId\":\"" + runId + "\",\"status\":\"DONE\",\"scenarios\":8,"
                        + "\"wordsP90\":27,\"gatePassed\":true}").build());
        mockMvc.perform(get("/api/admin/jarvis/oral-bench/" + runId).contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.wordsP90").value(27))
                .andExpect(jsonPath("$.gatePassed").value(true));
        mockMvc.perform(get("/api/admin/jarvis/oral-bench/" + runId).contextPath("/api")
                        .header("Authorization", "Bearer " + jwtService.generateToken(other)))
                .andExpect(status().isNotFound());
        assertThat(service.report(other.getId(), runId)).isEmpty();
    }
}
