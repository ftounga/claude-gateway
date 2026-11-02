package fr.claudegateway.jarvis.capture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** La capture réelle des énoncés (F-239 / SF-239-03) : interrupteur ADMIN, garde, isolation, effacement. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisCaptureApiIntegrationTest {

    private static final byte[] WAV = new byte[] { 'R', 'I', 'F', 'F', 1, 2, 3, 4 };

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JarvisAudioCaptureRepository captures;

    private User user(String prefix, UserRole role) {
        return userRepository.save(User.builder().email(prefix + "-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(role).createdAt(OffsetDateTime.now()).build());
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.generateToken(user);
    }

    private ResultActions capture(String token) throws Exception {
        return mockMvc.perform(multipart("/api/jarvis/captures")
                .file(new MockMultipartFile("audio", "enonce.wav", "audio/wav", WAV))
                .param("seconds", "2").param("listenMode", "CONV_STREAM").param("provider", "elevenlabs-batch")
                .param("streamText", "Où en est Cagibi ?").param("finalText", "Où en est CAGIP ?")
                .contextPath("/api").header("Authorization", token));
    }

    private ResultActions toggle(String token, boolean on) throws Exception {
        return mockMvc.perform(put("/api/jarvis/preferences").contextPath("/api").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content("{\"captureAudio\":" + on + "}"));
    }

    @Test
    void nothingIsKeptUntilTheAdminSwitchesItOnThenOnlyTheOwnerReadsIt() throws Exception {
        User alice = user("capture-alice", UserRole.ADMIN);
        User bob = user("capture-bob", UserRole.ADMIN);
        String a = bearer(alice);

        capture(a).andExpect(status().isNoContent());
        assertThat(captures.findByUserIdOrderByCreatedAtDesc(alice.getId())).isEmpty();

        toggle(a, true).andExpect(status().isOk()).andExpect(jsonPath("$.captureAudio").value(true));
        capture(a).andExpect(status().isNoContent());
        var kept = captures.findByUserIdOrderByCreatedAtDesc(alice.getId());
        assertThat(kept).hasSize(1);
        assertThat(kept.get(0).getFinalText()).isEqualTo("Où en est CAGIP ?");
        UUID id = kept.get(0).getId();

        mockMvc.perform(get("/api/admin/jarvis/captures").contextPath("/api").header("Authorization", a))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].streamText").value("Où en est Cagibi ?"));
        mockMvc.perform(get("/api/admin/jarvis/captures/" + id + "/audio").contextPath("/api").header("Authorization", a))
                .andExpect(status().isOk()).andExpect(content().bytes(WAV));
        mockMvc.perform(get("/api/admin/jarvis/captures/" + id + "/audio").contextPath("/api")
                .header("Authorization", bearer(bob))).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/admin/jarvis/captures").contextPath("/api").header("Authorization", bearer(bob)))
                .andExpect(jsonPath("$.length()").value(0));

        // Éteindre efface tout, tout de suite.
        toggle(a, false).andExpect(status().isOk()).andExpect(jsonPath("$.captureAudio").value(false));
        assertThat(captures.findByUserIdOrderByCreatedAtDesc(alice.getId())).isEmpty();
    }

    @Test
    void aNonAdminCanNeitherSwitchItOnNorBeCaptured() throws Exception {
        User carol = user("capture-carol", UserRole.USER);
        String c = bearer(carol);

        toggle(c, true).andExpect(status().isForbidden());
        capture(c).andExpect(r -> assertThat(r.getResponse().getStatus()).isIn(204, 403));
        assertThat(captures.findByUserIdOrderByCreatedAtDesc(carol.getId())).isEmpty();
        mockMvc.perform(get("/api/admin/jarvis/captures").contextPath("/api").header("Authorization", c))
                .andExpect(status().isForbidden());
    }
}
