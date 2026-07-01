package fr.claudegateway.template;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Tests d'intégration de {@code /api/templates} (F-13 / SF-13-01) : CRUD, validations,
 * authentification et isolation {@code user_id} (un modèle d'autrui est indistinct d'un modèle
 * inexistant → 404). Aucun appel externe : simple CRUD relationnel.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TemplateApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private TemplateRepository templateRepository;

    @Autowired
    private JwtService jwtService;

    private User alice;
    private String aliceToken;
    private String bobToken;

    @BeforeEach
    void setUp() {
        templateRepository.deleteAll();
        userRepository.deleteAll();

        alice = userRepository.save(User.builder()
                .email("alice@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        User bob = userRepository.save(User.builder()
                .email("bob@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        aliceToken = jwtService.generateToken(alice);
        bobToken = jwtService.generateToken(bob);
    }

    private String body(String name, String category, String content) {
        return "{\"name\":\"" + name + "\",\"category\":" + (category == null ? "null" : "\"" + category + "\"")
                + ",\"content\":\"" + content + "\"}";
    }

    @Test
    void createReturns201WithTemplate() throws Exception {
        mockMvc.perform(post("/api/templates").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Audit sécurité", "AUDIT", "Réalise un audit...")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", notNullValue()))
                .andExpect(jsonPath("$.name", is("Audit sécurité")))
                .andExpect(jsonPath("$.category", is("AUDIT")))
                .andExpect(jsonPath("$.content", is("Réalise un audit...")));
    }

    @Test
    void createDefaultsCategoryToOtherWhenNull() throws Exception {
        mockMvc.perform(post("/api/templates").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Sans catégorie", null, "corps")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.category", is("OTHER")));
    }

    @Test
    void createRejectsBlankName() throws Exception {
        mockMvc.perform(post("/api/templates").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("   ", "AUDIT", "corps")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }

    @Test
    void createRejectsBlankContent() throws Exception {
        mockMvc.perform(post("/api/templates").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Nom", "AUDIT", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }

    @Test
    void createRejectsInvalidCategoryWith400() throws Exception {
        mockMvc.perform(post("/api/templates").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Nom", "WRONG", "corps")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error", is("validation_error")));
    }

    @Test
    void listReturnsOnlyOwnTemplates() throws Exception {
        String aliceId = create(aliceToken, "Modèle Alice", "AUDIT", "a");
        create(bobToken, "Modèle Bob", "REPORT", "b");

        mockMvc.perform(get("/api/templates").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id", is(aliceId)))
                .andExpect(jsonPath("$[0].name", is("Modèle Alice")));
    }

    @Test
    void getForeignTemplateReturns404() throws Exception {
        String bobId = create(bobToken, "Modèle Bob", "REPORT", "b");

        mockMvc.perform(get("/api/templates/" + bobId).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error", is("not_found")));
    }

    @Test
    void updateOwnedTemplateReturns200() throws Exception {
        String id = create(aliceToken, "Ancien", "AUDIT", "vieux");

        mockMvc.perform(put("/api/templates/" + id).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Nouveau", "REPORT", "neuf")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Nouveau")))
                .andExpect(jsonPath("$.category", is("REPORT")))
                .andExpect(jsonPath("$.content", is("neuf")));
    }

    @Test
    void updateForeignTemplateReturns404AndDoesNotWrite() throws Exception {
        String bobId = create(bobToken, "Modèle Bob", "REPORT", "b");

        mockMvc.perform(put("/api/templates/" + bobId).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Piraté", "AUDIT", "x")))
                .andExpect(status().isNotFound());

        PromptTemplate untouched = templateRepository.findById(UUID.fromString(bobId)).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(untouched.getName()).isEqualTo("Modèle Bob");
    }

    @Test
    void deleteOwnedTemplateReturns204() throws Exception {
        String id = create(aliceToken, "À supprimer", "OTHER", "x");

        mockMvc.perform(delete("/api/templates/" + id).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(templateRepository.findById(UUID.fromString(id))).isEmpty();
    }

    @Test
    void deleteForeignTemplateReturns404AndKeepsIt() throws Exception {
        String bobId = create(bobToken, "Modèle Bob", "REPORT", "b");

        mockMvc.perform(delete("/api/templates/" + bobId).contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isNotFound());

        org.assertj.core.api.Assertions.assertThat(templateRepository.findById(UUID.fromString(bobId))).isPresent();
    }

    @Test
    void requestsWithoutTokenAreRejected() throws Exception {
        mockMvc.perform(get("/api/templates").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    /** Crée un modèle via l'API et renvoie son id (extrait du JSON de réponse). */
    private String create(String token, String name, String category, String content) throws Exception {
        String response = mockMvc.perform(post("/api/templates").contextPath("/api")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(name, category, content)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(response, "$.id");
    }
}
