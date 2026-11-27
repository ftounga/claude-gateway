package fr.claudegateway.atelier.gallery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceExecutionTarget;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.WorkspaceSource;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-278 / SF-278-01 — l'aperçu d'une image de galerie : servi en JPEG à son propriétaire, introuvable pour un autre
 * compte (indiscernable d'une image inexistante), effacé avec le terminal.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GalleryApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private GalleryService gallery;
    @Autowired private JwtService jwtService;
    @MockitoBean private RunnerToolGateway runner;

    private UUID aliceId;
    private String aliceToken;
    private UUID aliceTerminal;
    private String bobToken;
    private UUID bobTerminal;

    @BeforeEach
    void setUp() throws Exception {
        workspaceRepository.deleteAll();
        userRepository.deleteAll();
        User alice = seedUser("alice-gallery@example.com");
        aliceId = alice.getId();
        aliceToken = jwtService.generateToken(alice);
        aliceTerminal = seedTerminal(aliceId);
        User bob = seedUser("bob-gallery@example.com");
        bobToken = jwtService.generateToken(bob);
        bobTerminal = seedTerminal(bob.getId());
        byte[] png = GalleryImagesTest.png(64, 48, false, false);
        when(runner.readFileBytes(any(), anyString(), eq(".atelier/entrees/a.png"), anyLong(), anyInt()))
                .thenReturn(new RunnerCallResult(true, Base64.getEncoder().encodeToString(png), false, null, 2,
                        (long) png.length, null, null, null, false));
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }

    private UUID seedTerminal(UUID userId) {
        return workspaceRepository.save(Workspace.builder().userId(userId).name("Projet").projectPath("projet")
                .source(WorkspaceSource.LOCAL).executionTarget(WorkspaceExecutionTarget.RUNNER).build()).getId();
    }

    private String url(UUID workspaceId, String imageId) {
        return "/api/workspaces/" + workspaceId + "/gallery/" + imageId;
    }

    private String publish() {
        GalleryService.Result result = gallery.publish(aliceId, aliceTerminal,
                new RunnerTarget(UUID.randomUUID(), aliceTerminal, "projet"), "toolu_1", "Captures",
                List.of(new GalleryService.Request(".atelier/entrees/a.png", "Légende", null)), List.of(),
                System.currentTimeMillis() + 600_000);
        assertThat(result.block()).isNotNull();
        return result.block().items().get(0).id();
    }

    @Test
    @DisplayName("l'aperçu se lit, en JPEG, par son propriétaire")
    void ownerReadsTheImage() throws Exception {
        String id = publish();
        byte[] body = mockMvc.perform(get(url(aliceTerminal, id)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/jpeg"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(body[0]).isEqualTo((byte) 0xFF);
        assertThat(body[1]).isEqualTo((byte) 0xD8);
    }

    @Test
    @DisplayName("L'IMAGE D'UN AUTRE COMPTE EST INTROUVABLE — par son terminal comme par celui d'Alice")
    void anotherAccountGets404() throws Exception {
        String id = publish();
        mockMvc.perform(get(url(bobTerminal, id)).contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(url(aliceTerminal, id)).contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("un identifiant invalide ou inconnu rend 404 ; sans jeton, refusé")
    void unknownOrAnonymous() throws Exception {
        mockMvc.perform(get(url(aliceTerminal, "zz")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(url(aliceTerminal, "0123456789abcdef0123456789abcdef")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
        int anonymous = mockMvc.perform(get(url(aliceTerminal, publish())).contextPath("/api"))
                .andReturn().getResponse().getStatus();
        assertThat(anonymous).isIn(401, 403);
    }

    @Test
    @DisplayName("les aperçus s'en vont avec le terminal")
    void deletedWithTheTerminal() {
        String id = publish();
        assertThat(gallery.image(aliceId, aliceTerminal, id)).isPresent();
        workspaceService.delete(aliceId, aliceTerminal);
        assertThat(gallery.image(aliceId, aliceTerminal, id)).isEmpty();
    }
}
