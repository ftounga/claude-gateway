package fr.claudegateway.diagrams;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.video.VideoIconSource;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-238 / SF-238-08 — {@code GET /api/diagrams/icons/{chemin}.png} : icône servie avec un cache immuable, nom hors
 * motif ou traversée ⇒ 400 sans appel au renderer, icône absente ⇒ 404, jeton requis.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DiagramIconApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwt;
    @MockitoBean private VideoIconSource source;

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3};
    private String token;

    @BeforeEach
    void seed() {
        User user = users.save(User.builder().email("icons-" + UUID.randomUUID() + "@ex.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).createdAt(OffsetDateTime.now()).build());
        token = jwt.generateToken(user);
    }

    @Test
    @DisplayName("200 : image/png, cache public immuable d'un an, nosniff")
    void served() throws Exception {
        when(source.fetch(List.of("aws/database/rds"))).thenReturn(new VideoIconSource.Batch(
                List.of(new VideoIconSource.Icon("aws/database/rds", PNG)), List.of(), List.of()));

        mockMvc.perform(get("/api/diagrams/icons/aws/database/rds.png").contextPath("/api")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PNG))
                .andExpect(header().string("Cache-Control", Matchers.allOf(Matchers.containsString("max-age=31536000"),
                        Matchers.containsString("public"), Matchers.containsString("immutable"))))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    @DisplayName("nom hors motif ⇒ 400, renderer jamais appelé")
    void outsideThePattern() throws Exception {
        for (String path : List.of("/api/diagrams/icons/AWS/database/rds.png", "/api/diagrams/icons/evil/x/y.png",
                "/api/diagrams/icons/aws.png", "/api/diagrams/icons/aws/a/b/c/d.png",
                "/api/diagrams/icons/aws/database/rds.svg")) {
            mockMvc.perform(get(path).contextPath("/api").header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }
        verify(source, never()).fetch(any());
    }

    @Test
    @DisplayName("traversée (brute ou encodée) ⇒ 400, renderer jamais appelé")
    void traversal() throws Exception {
        for (String path : List.of("/api/diagrams/icons/aws/../../etc/passwd.png",
                "/api/diagrams/icons/aws/%2e%2e/%2e%2e/etc/passwd.png", "/api/diagrams/icons/aws/x%2f..%2fy/z.png")) {
            mockMvc.perform(get(URI.create(path)).contextPath("/api").header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }
        verify(source, never()).fetch(any());
    }

    @Test
    @DisplayName("icône absente ⇒ 404 (jamais une autre icône)")
    void missing() throws Exception {
        when(source.fetch(any())).thenReturn(new VideoIconSource.Batch(List.of(), List.of("aws/database/nope"),
                List.of()));

        mockMvc.perform(get("/api/diagrams/icons/aws/database/nope.png").contextPath("/api")
                .header("Authorization", "Bearer " + token)).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("renderer muet ⇒ 503 ; sans jeton ⇒ 401")
    void unavailableAndUnauthenticated() throws Exception {
        when(source.fetch(any())).thenThrow(new DiagramRendererUnavailableException("muet"));

        mockMvc.perform(get("/api/diagrams/icons/aws/compute/ec2.png").contextPath("/api")
                .header("Authorization", "Bearer " + token)).andExpect(status().isServiceUnavailable());
        mockMvc.perform(get("/api/diagrams/icons/aws/compute/ec2.png").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }
}
