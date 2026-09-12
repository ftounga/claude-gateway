package fr.claudegateway.teams.block;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.WorkspaceSource;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * <b>L'image d'un moment</b> (F-89 / SF-89-02), servie et effacée.
 *
 * <p>Trois choses s'y jouent : elle se lit, <b>l'image d'un autre compte est introuvable</b> — et
 * indiscernable d'une image inexistante —, et elle <b>part avec le compte rendu</b> (D2).</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TeamsMomentApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private WorkspaceService workspaceService;
    @Autowired private TeamsMomentImageService images;
    @Autowired private JwtService jwtService;

    private static final byte[] PIXEL = "png-bytes".getBytes(StandardCharsets.UTF_8);

    private UUID aliceId;
    private String aliceToken;
    private UUID aliceTerminal;
    private String bobToken;
    private UUID bobTerminal;

    @BeforeEach
    void setUp() {
        workspaceRepository.deleteAll();
        userRepository.deleteAll();

        User alice = seedUser("alice-moments@example.com");
        aliceId = alice.getId();
        aliceToken = jwtService.generateToken(alice);
        aliceTerminal = seedTeamsTerminal(alice.getId());

        User bob = seedUser("bob-moments@example.com");
        bobToken = jwtService.generateToken(bob);
        bobTerminal = seedTeamsTerminal(bob.getId());
    }

    private User seedUser(String email) {
        // ADMIN : l'accès Forge n'est pas le sujet ici, et un refus d'accès masquerait le test.
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
    }

    private UUID seedTeamsTerminal(UUID userId) {
        return workspaceRepository.save(Workspace.builder()
                .userId(userId).name("Terminal Teams").projectPath("")
                .source(WorkspaceSource.LOCAL).teamsTerminal(true).build()).getId();
    }

    private String url(UUID workspaceId, String imageId) {
        return "/api/workspaces/" + workspaceId + "/teams/moments/" + imageId;
    }

    @Test
    @DisplayName("l'image se lit avec son type")
    void theImageIsServed() throws Exception {
        String imageId = images.store(aliceId, aliceTerminal, "image/png", PIXEL);

        mockMvc.perform(get(url(aliceTerminal, imageId)).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(content().contentType("image/png"))
                .andExpect(content().bytes(PIXEL));
    }

    @Test
    @DisplayName("L'IMAGE D'UN AUTRE COMPTE EST INTROUVABLE — comme une image qui n'existe pas")
    void anotherAccountsImageIs404() throws Exception {
        String imageId = images.store(aliceId, aliceTerminal, "image/png", PIXEL);

        // Bob demande l'image d'Alice sur SON terminal à lui : 404 — l'image n'est pas là.
        mockMvc.perform(get(url(bobTerminal, imageId)).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());

        // Et sur le terminal d'Alice : 404 aussi, parce que le terminal ne lui appartient pas —
        // les deux refus sont indiscernables, et c'est voulu.
        mockMvc.perform(get(url(aliceTerminal, imageId)).contextPath("/api")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void anUnknownImageIs404() throws Exception {
        mockMvc.perform(get(url(aliceTerminal, "jamaisdeposee")).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("D2 — supprimer le terminal Teams efface ses images")
    void deletingTheTerminalErasesItsImages() throws Exception {
        String imageId = images.store(aliceId, aliceTerminal, "image/png", PIXEL);
        assertThat(images.exists(aliceId, aliceTerminal, imageId)).isTrue();

        workspaceService.delete(aliceId, aliceTerminal);

        assertThat(images.exists(aliceId, aliceTerminal, imageId)).isFalse();
        assertThat(images.list(aliceId, aliceTerminal)).isEmpty();
    }
}
