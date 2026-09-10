package fr.claudegateway.governance;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-51 / SF-51-01 — le catalogue de bout en bout : l'admin rédige et publie, l'utilisateur lit.
 *
 * <p>Ce qui est vérifié ici et nulle part ailleurs : un utilisateur ordinaire ne peut <b>rien</b>
 * écrire dans le catalogue (403 sur chaque geste d'administration), il ne voit pas les brouillons, et
 * le contenu des fichiers ne sort jamais côté utilisateur — il n'en a pas besoin pour décider.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GovernanceCatalogApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private GovernancePackageRepository packages;
    @Autowired
    private GovernancePackageFileRepository files;
    @Autowired
    private ObjectMapper objectMapper;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        files.deleteAll();
        packages.deleteAll();
        userRepository.deleteAll();

        User admin = userRepository.save(User.builder()
                .email("gov-admin@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.ADMIN).build());
        adminToken = jwtService.generateToken(admin);

        User user = userRepository.save(User.builder()
                .email("gov-user@example.com").emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        userToken = jwtService.generateToken(user);
    }

    @Test
    @DisplayName("l'admin rédige, publie, et l'utilisateur lit — sans le contenu des fichiers")
    void adminPublishesAndUserReads() throws Exception {
        String created = mockMvc.perform(post("/api/admin/governance/packages").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("gouvernance-livrables")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.published").value(false))
                .andExpect(jsonPath("$.files[0].content").value("# État du sujet\n"))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asText();

        // Tant qu'il n'est pas publié, il n'existe pour personne d'autre.
        mockMvc.perform(get("/api/governance/packages").contextPath("/api")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(0)));

        mockMvc.perform(post("/api/admin/governance/packages/" + id + "/publish").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.published").value(true));

        String catalog = mockMvc.perform(get("/api/governance/packages").contextPath("/api")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", Matchers.hasSize(1)))
                .andExpect(jsonPath("$[0].slug").value("gouvernance-livrables"))
                .andExpect(jsonPath("$[0].rules", Matchers.notNullValue()))
                .andExpect(jsonPath("$[0].files[0].path").value("STATE.md"))
                .andExpect(jsonPath("$[0].files[0].kind").value("TEMPLATE"))
                .andReturn().getResponse().getContentAsString();
        // Le contenu d'un fichier n'a rien à faire dans le catalogue : l'écran annonce OÙ, pas QUOI.
        JsonNode file = objectMapper.readTree(catalog).get(0).get("files").get(0);
        org.assertj.core.api.Assertions.assertThat(file.has("content")).isFalse();

        // Dépublier retire du catalogue, sans rien détruire.
        mockMvc.perform(post("/api/admin/governance/packages/" + id + "/unpublish").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/governance/packages").contextPath("/api")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(jsonPath("$", Matchers.hasSize(0)));
    }

    @Test
    @DisplayName("un utilisateur ordinaire ne peut rien écrire dans le catalogue")
    void plainUserCannotWriteCatalog() throws Exception {
        mockMvc.perform(get("/api/admin/governance/packages").contextPath("/api")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/admin/governance/controls").contextPath("/api")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/governance/packages").contextPath("/api")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("tentative")))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/admin/governance/packages/" + java.util.UUID.randomUUID())
                        .contextPath("/api")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("tentative")))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/admin/governance/packages/" + java.util.UUID.randomUUID())
                        .contextPath("/api")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("le catalogue exige d'être connecté")
    void anonymousIsUnauthorized() throws Exception {
        mockMvc.perform(get("/api/governance/packages").contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("un slug déjà pris rend 409")
    void duplicateSlugConflicts() throws Exception {
        mockMvc.perform(post("/api/admin/governance/packages").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("doublon")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/admin/governance/packages").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("doublon")))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("supprimer un paquet publié rend 409, le supprimer une fois dépublié rend 204")
    void deleteRequiresUnpublish() throws Exception {
        String created = mockMvc.perform(post("/api/admin/governance/packages").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("a-supprimer")))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asText();

        mockMvc.perform(post("/api/admin/governance/packages/" + id + "/publish").contextPath("/api")
                .header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/admin/governance/packages/" + id).contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isConflict());

        mockMvc.perform(post("/api/admin/governance/packages/" + id + "/unpublish").contextPath("/api")
                .header("Authorization", "Bearer " + adminToken)).andExpect(status().isOk());
        mockMvc.perform(delete("/api/admin/governance/packages/" + id).contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("un paquet inexistant rend 404")
    void unknownPackageIsNotFound() throws Exception {
        mockMvc.perform(post("/api/admin/governance/packages/" + java.util.UUID.randomUUID()
                        + "/publish").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("un chemin qui sort du projet et un contrôle inconnu sont refusés en 400")
    void dangerousContentIsRejected() throws Exception {
        String escaping = """
                {"slug":"mauvais","name":"Mauvais","rules":null,"controlIds":[],
                 "files":[{"path":"../ailleurs/STATE.md","kind":"TEMPLATE","content":"x"}]}
                """;
        mockMvc.perform(post("/api/admin/governance/packages").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(escaping))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_governance_package"));

        String unknownControl = """
                {"slug":"mauvais2","name":"Mauvais","rules":null,
                 "controlIds":["controle-imaginaire"],"files":[]}
                """;
        mockMvc.perform(post("/api/admin/governance/packages").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(unknownControl))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", Matchers.containsString("Contrôle inconnu")));
    }

    @Test
    @DisplayName("modifier remplace le contenu et incrémente la version")
    void updateReplacesContent() throws Exception {
        String created = mockMvc.perform(post("/api/admin/governance/packages").contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("a-modifier")))
                .andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(created).get("id").asText();

        String replaced = """
                {"slug":"ignore","name":"Renommé","summary":"Deuxième rédaction",
                 "rules":"De nouvelles règles.","controlIds":[],
                 "files":[{"path":".claude/skills/explique.md","kind":"SKILL","content":"# explique"}]}
                """;
        mockMvc.perform(put("/api/admin/governance/packages/" + id).contextPath("/api")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(replaced))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.slug").value("a-modifier"))
                .andExpect(jsonPath("$.files", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.files[0].path").value(".claude/skills/explique.md"));
    }

    /** Un paquet minimal mais complet : une règle, un gabarit. */
    private static String body(String slug) {
        return """
                {"slug":"%s","name":"Livrables sans trace","summary":"La règle des livrables.",
                 "rules":"Aucun livrable ne doit suggérer un LLM.","controlIds":[],
                 "files":[{"path":"STATE.md","kind":"TEMPLATE","content":"# État du sujet\\n"}]}
                """.formatted(slug);
    }
}
