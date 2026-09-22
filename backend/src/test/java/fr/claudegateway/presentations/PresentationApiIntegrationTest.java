package fr.claudegateway.presentations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Les présentations d'un lieu : lister, télécharger le vrai .pptx, supprimer — isolées par compte
 * (F-129 / SF-129-02). Un utilisateur B ne voit, ne télécharge, ni ne supprime jamais celles de A.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PresentationApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private PresentationRepository presentationRepository;
    @Autowired private PresentationService presentationService;
    @Autowired private PresentationStore store;
    @Autowired private fr.claudegateway.atelier.storage.WorkspaceStorage storage;
    @Autowired private JwtService jwtService;

    private UUID alice;
    private String aliceToken;
    private String bobToken;
    private final UUID host = UUID.randomUUID();
    private UUID deck;

    @BeforeEach
    void setUp() {
        presentationRepository.deleteAll();
        User aliceUser = seed("alice-pptx@example.com");
        alice = aliceUser.getId();
        aliceToken = jwtService.generateToken(aliceUser);
        bobToken = jwtService.generateToken(seed("bob-pptx@example.com"));
        deck = presentationService.publish(new PresentationPlace(alice, PresentationSpace.VIGIE, host, null),
                null, "Onboarding CI/CD", "Neuf slides", pptx("deck-v1")).getId();
    }

    private User seed(String email) {
        return userRepository.findByEmail(email).orElseGet(() -> userRepository.save(User.builder().email(email)
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build()));
    }

    private MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    /** Un .pptx minimal crédible : l'en-tête ZIP « PK\x03\x04 » suivi d'un marqueur. */
    private static byte[] pptx(String marker) {
        byte[] payload = marker.getBytes(StandardCharsets.UTF_8);
        byte[] content = new byte[4 + payload.length];
        content[0] = 0x50;
        content[1] = 0x4B;
        content[2] = 0x03;
        content[3] = 0x04;
        System.arraycopy(payload, 0, content, 4, payload.length);
        return content;
    }

    @Test
    @DisplayName("CA3 — la liste d'un lieu : ce compte, ce poste, cet espace ; Bob ne voit rien")
    void listByPlace() throws Exception {
        mockMvc.perform(as(get("/api/presentations").param("hostId", host.toString()).param("space", "vigie"),
                        aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value(deck.toString()))
                .andExpect(jsonPath("$[0].title").value("Onboarding CI/CD"))
                .andExpect(jsonPath("$[0].slideCount").doesNotExist());
        mockMvc.perform(as(get("/api/presentations").param("hostId", host.toString()).param("space", "VIGIE"),
                        bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
        mockMvc.perform(as(get("/api/presentations").param("hostId", host.toString()).param("space", "AILLEURS"),
                        aliceToken))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("CA4 — télécharger le vrai .pptx : bon content-type et pièce jointe")
    void downloadPptx() throws Exception {
        byte[] body = mockMvc.perform(as(get("/api/presentations/" + deck + "/pptx"), aliceToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", PresentationStore.PPTX_CONTENT_TYPE))
                .andExpect(header().string("Content-Disposition", containsString("attachment")))
                .andExpect(header().string("Content-Disposition", containsString(".pptx")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(body).startsWith((byte) 0x50, (byte) 0x4B, (byte) 0x03, (byte) 0x04);
    }

    @Test
    @DisplayName("CA5 — isolation : Bob ne lit, ne télécharge, ni ne supprime la présentation d'Alice (404)")
    void isolationCrossUser() throws Exception {
        mockMvc.perform(as(get("/api/presentations/" + deck), bobToken)).andExpect(status().isNotFound());
        mockMvc.perform(as(get("/api/presentations/" + deck + "/pptx"), bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(delete("/api/presentations/" + deck), bobToken)).andExpect(status().isNotFound());
        // Rien n'a été effacé chez Alice.
        assertThat(presentationRepository.findById(deck)).isPresent();
    }

    @Test
    @DisplayName("CA6 — remplacer par presentation_id : même entrée, nouveau fichier, pas de doublon")
    void replaceById() {
        Presentation replaced = presentationService.publish(
                new PresentationPlace(alice, PresentationSpace.VIGIE, host, null), deck, "Onboarding CI/CD v2",
                null, pptx("deck-v2"));
        assertThat(replaced.getId()).isEqualTo(deck);
        assertThat(presentationService.list(alice, host, PresentationSpace.VIGIE)).hasSize(1);
        assertThat(new String(store.pptx(alice, deck).orElseThrow(), StandardCharsets.UTF_8))
                .contains("deck-v2");
    }

    @Test
    @DisplayName("CA4/CA5 — supprimer : 204, et les objets du stockage disparaissent")
    void deleteRemovesObjects() throws Exception {
        assertThat(storage.listKeys(PresentationStore.PREFIX + alice + "/" + deck + "/")).isNotEmpty();
        mockMvc.perform(as(delete("/api/presentations/" + deck), aliceToken)).andExpect(status().isNoContent());
        mockMvc.perform(as(get("/api/presentations/" + deck), aliceToken)).andExpect(status().isNotFound());
        assertThat(storage.listKeys(PresentationStore.PREFIX + alice + "/" + deck + "/")).isEmpty();
    }
}
