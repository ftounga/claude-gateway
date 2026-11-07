package fr.claudegateway.jarvis.trace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;
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

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** Le journal de l'écoute (F-233 / SF-233-15) : 204, rangé sous le compte du jeton, isolé. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisListenTraceApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JarvisListenTraceRepository traces;
    @Autowired private JwtService jwtService;

    private User owner;
    private User other;

    @BeforeEach
    void setUp() {
        owner = user("jarvis-trace-a-" + UUID.randomUUID() + "@ex.com");
        other = user("jarvis-trace-b-" + UUID.randomUUID() + "@ex.com");
    }

    private User user(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true).provider(AuthProvider.LOCAL)
                .role(UserRole.ADMIN).createdAt(OffsetDateTime.now()).build());
    }

    private ResultActions send(User user, String json) throws Exception {
        return mockMvc.perform(post("/api/jarvis/listen-trace").contextPath("/api")
                .header("Authorization", "Bearer " + jwtService.generateToken(user))
                .contentType(MediaType.APPLICATION_JSON).content(json));
    }

    @Test
    @DisplayName("un lot : 204, rangé sous le compte du jeton, rien chez l'autre compte")
    void records() throws Exception {
        send(owner, "{\"pageId\":\"p-1\",\"version\":\"TJGIBB4X\",\"companion\":true,\"events\":["
                + "{\"seq\":1,\"t\":10,\"ev\":\"page_load\",\"st\":\"repos\",\"md\":\"idle\",\"d\":\"sw=1\"},"
                + "{\"seq\":2,\"t\":900,\"ev\":\"listen_cancel\",\"st\":\"ecoute\",\"md\":\"active\",\"d\":\"silence\","
                + "\"n\":4}]}")
                .andExpect(status().isNoContent());
        List<JarvisListenTraceEvent> mine = traces.findByUserIdOrderByCreatedAtAscSeqAsc(owner.getId());
        assertThat(mine).extracting(JarvisListenTraceEvent::getEvent).containsExactly("page_load", "listen_cancel");
        assertThat(mine.get(1).getRepeatCount()).isEqualTo(4);
        assertThat(mine.get(0).isCompanion()).isTrue();
        assertThat(traces.findByUserIdOrderByCreatedAtAscSeqAsc(other.getId())).isEmpty();
    }

    @Test
    @DisplayName("corps absent : 204, rien rangé")
    void emptyBody() throws Exception {
        send(owner, "{}").andExpect(status().isNoContent());
        assertThat(traces.findByUserIdOrderByCreatedAtAscSeqAsc(owner.getId())).isEmpty();
    }

    @Test
    @DisplayName("sans jeton : refusé")
    void anonymous() throws Exception {
        int code = mockMvc.perform(post("/api/jarvis/listen-trace").contextPath("/api")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andReturn().getResponse().getStatus();
        assertThat(code).isIn(401, 403);
    }
}
