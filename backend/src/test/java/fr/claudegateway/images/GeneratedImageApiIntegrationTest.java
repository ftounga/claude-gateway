package fr.claudegateway.images;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Les images générées d'un lieu : lister, lire le statut, servir le PNG, supprimer — isolées par compte
 * (F-142 / SF-142-04). Un utilisateur B ne voit, ne lit, ni ne supprime jamais celles de A.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GeneratedImageApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private GeneratedImageRepository imageRepository;
    @Autowired private GeneratedImageStore store;
    @Autowired private fr.claudegateway.atelier.storage.WorkspaceStorage storage;
    @Autowired private JwtService jwtService;

    private UUID alice;
    private String aliceToken;
    private String bobToken;
    private final UUID host = UUID.randomUUID();
    private UUID imageId;

    @BeforeEach
    void setUp() {
        imageRepository.deleteAll();
        User aliceUser = seed("alice-img@example.com");
        alice = aliceUser.getId();
        aliceToken = jwtService.generateToken(aliceUser);
        bobToken = jwtService.generateToken(seed("bob-img@example.com"));

        GeneratedImage image = imageRepository.save(GeneratedImage.builder()
                .userId(alice).space(ImageSpace.FORGE).hostId(host).workspaceId(null)
                .prompt("une couverture bleue").size("1024x1024").status(GeneratedImageStatus.READY)
                .imageBytes(3L).build());
        imageId = image.getId();
        image.setImageKey(GeneratedImageStore.imageKey(alice, imageId));
        imageRepository.save(image);
        store.putImage(alice, imageId, new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47});
    }

    private User seed(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> userRepository.save(User.builder().email(email)
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build()));
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    @Test
    @DisplayName("la liste d'un lieu : ce compte, ce poste, cet espace ; Bob ne voit rien")
    void listByPlace() throws Exception {
        mockMvc.perform(as(get("/api/generated-images").param("hostId", host.toString()).param("space", "forge"),
                        aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(imageId.toString()))
                .andExpect(jsonPath("$[0].status").value("READY"))
                .andExpect(jsonPath("$[0].size").value("1024x1024"));
        mockMvc.perform(as(get("/api/generated-images").param("hostId", host.toString()).param("space", "FORGE"),
                        bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(as(get("/api/generated-images").param("hostId", host.toString()).param("space", "AILLEURS"),
                        aliceToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("servir le PNG : bon content-type ; isolation : Bob → 404")
    void serveImage() throws Exception {
        mockMvc.perform(as(get("/api/generated-images/" + imageId + "/image"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"));
        mockMvc.perform(as(get("/api/generated-images/" + imageId + "/image"), bobToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("isolation : Bob ne lit, ne sert, ni ne supprime l'image d'Alice (404)")
    void isolationCrossUser() throws Exception {
        mockMvc.perform(as(get("/api/generated-images/" + imageId), bobToken)).andExpect(status().isNotFound());
        mockMvc.perform(as(get("/api/generated-images/" + imageId + "/image"), bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(delete("/api/generated-images/" + imageId), bobToken)).andExpect(status().isNotFound());
        assertThat(imageRepository.findById(imageId)).isPresent();
    }

    @Test
    @DisplayName("supprimer : 204, et les objets du stockage disparaissent")
    void deleteRemovesObjects() throws Exception {
        assertThat(storage.listKeys(GeneratedImageStore.PREFIX + alice + "/" + imageId + "/")).isNotEmpty();
        mockMvc.perform(as(delete("/api/generated-images/" + imageId), aliceToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(as(get("/api/generated-images/" + imageId), aliceToken)).andExpect(status().isNotFound());
        assertThat(storage.listKeys(GeneratedImageStore.PREFIX + alice + "/" + imageId + "/")).isEmpty();
    }
}
