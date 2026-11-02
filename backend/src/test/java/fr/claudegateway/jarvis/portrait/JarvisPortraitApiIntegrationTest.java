package fr.claudegateway.jarvis.portrait;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.account.AccountService;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-241 / SF-241-01 — {@code /api/jarvis/portrait} : CRUD, borne, chiffrement en base, isolation A/B, tout effacer,
 * purge du compte, 401.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisPortraitApiIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AccountService accountService;
    @Autowired private JarvisAllusionService allusionService;

    private record Account(UUID id, String bearer) {
    }

    private Account account(String prefix) {
        User user = userRepository.save(User.builder().email(prefix + "-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        return new Account(user.getId(), "Bearer " + jwtService.generateToken(user));
    }

    private JsonNode json(MvcResult r) throws Exception {
        return JSON.readTree(r.getResponse().getContentAsString());
    }

    private String addSouvenir(Account a, String text) throws Exception {
        MvcResult r = mockMvc.perform(post("/api/jarvis/portrait/souvenirs").contextPath("/api")
                        .header("Authorization", a.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + text + "\",\"sensitive\":true}"))
                .andExpect(status().isCreated()).andReturn();
        return json(r).get("id").asText();
    }

    @Test
    void emptyThenWriteReadAndDateOnlyChangedSections() throws Exception {
        Account a = account("portrait-rw");
        mockMvc.perform(get("/api/jarvis/portrait").contextPath("/api").header("Authorization", a.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.exists").value(false))
                .andExpect(jsonPath("$.sections.length()").value(7))
                .andExpect(jsonPath("$.sections[1].key").value("ENTOURAGE"))
                .andExpect(jsonPath("$.sections[1].sensitive").value(true))
                .andExpect(jsonPath("$.size.maxTokens").value(1500));

        mockMvc.perform(put("/api/jarvis/portrait").contextPath("/api").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sections\":{\"QUI\":\"Chef de projet\",\"GOUTS\":\"Le jazz\"}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.exists").value(true))
                .andExpect(jsonPath("$.sections[0].text").value("Chef de projet"))
                .andExpect(jsonPath("$.enabled").value(true));
        MvcResult first = mockMvc.perform(get("/api/jarvis/portrait").contextPath("/api")
                .header("Authorization", a.bearer())).andReturn();
        String gouts = json(first).get("sections").get(3).get("updatedAt").asText();
        long version = json(first).get("version").asLong();

        mockMvc.perform(put("/api/jarvis/portrait").contextPath("/api").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sections\":{\"QUI\":\"Directeur de projet\",\"GOUTS\":\"Le jazz\"},"
                                + "\"enabled\":false}"))
                .andExpect(status().isOk());
        JsonNode second = json(mockMvc.perform(get("/api/jarvis/portrait").contextPath("/api")
                .header("Authorization", a.bearer())).andReturn());
        assertThat(second.get("sections").get(0).get("text").asText()).isEqualTo("Directeur de projet");
        assertThat(second.get("sections").get(3).get("updatedAt").asText()).isEqualTo(gouts);
        assertThat(second.get("enabled").asBoolean()).isFalse();
        assertThat(second.get("version").asLong()).isGreaterThan(version);
    }

    @Test
    void tooLargeUnknownSectionAndEmptyBodyAreRefused() throws Exception {
        Account a = account("portrait-400");
        String big = "x".repeat(JarvisPortraitRules.MAX_CHARS + 1);
        mockMvc.perform(put("/api/jarvis/portrait").contextPath("/api").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sections\":{\"QUI\":\"" + big + "\"}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("portrait_too_large"));
        mockMvc.perform(put("/api/jarvis/portrait").contextPath("/api").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sections\":{\"SECRETS\":\"x\"}}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error").value("invalid_portrait"));
        mockMvc.perform(put("/api/jarvis/portrait").contextPath("/api").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/jarvis/portrait/souvenirs").contextPath("/api").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/jarvis/portrait/souvenirs").contextPath("/api").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"" + "y".repeat(201) + "\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/jarvis/portrait").contextPath("/api")).andExpect(status().isUnauthorized());
    }

    @Test
    void souvenirsCrudIntegrateAndMemoryFull() throws Exception {
        Account a = account("portrait-souv");
        String first = addSouvenir(a, "Ma fille est nee en mars");
        String second = addSouvenir(a, "Aime le jazz manouche");
        mockMvc.perform(get("/api/jarvis/portrait").contextPath("/api").header("Authorization", a.bearer()))
                .andExpect(jsonPath("$.souvenirs.length()").value(2))
                .andExpect(jsonPath("$.souvenirs[0].number").value(1))
                .andExpect(jsonPath("$.souvenirs[0].source").value("ECRAN"))
                .andExpect(jsonPath("$.souvenirs[1].number").value(2));
        mockMvc.perform(patch("/api/jarvis/portrait/souvenirs/" + second).contextPath("/api")
                        .header("Authorization", a.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Aime le jazz\",\"sensitive\":false}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("Aime le jazz"))
                .andExpect(jsonPath("$.sensitive").value(false));
        mockMvc.perform(post("/api/jarvis/portrait/souvenirs/" + first + "/integrate").contextPath("/api")
                        .header("Authorization", a.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"ENTOURAGE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections[1].text").value("Ma fille est nee en mars"))
                .andExpect(jsonPath("$.souvenirs.length()").value(1));
        mockMvc.perform(delete("/api/jarvis/portrait/souvenirs/" + second).contextPath("/api")
                .header("Authorization", a.bearer())).andExpect(status().isNoContent());
        for (int i = 0; i < JarvisPortraitRules.MAX_SOUVENIRS; i++) {
            addSouvenir(a, "souvenir " + i);
        }
        mockMvc.perform(post("/api/jarvis/portrait/souvenirs").contextPath("/api").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"un de trop\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("souvenirs_full"))
                .andExpect(jsonPath("$.message").value(JarvisPortraitRules.MEMORY_FULL));
    }

    @Test
    void columnsAreSealedInDatabase() throws Exception {
        Account a = account("portrait-seal");
        mockMvc.perform(put("/api/jarvis/portrait").contextPath("/api").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sections\":{\"ENTOURAGE\":\"Ma femme Claire-Anne\"}}"))
                .andExpect(status().isOk());
        addSouvenir(a, "Pere hospitalise a Lyon");
        String sections = jdbc.queryForObject("select sections_enc from jarvis_portraits where user_id = ?",
                String.class, a.id());
        List<String> texts = jdbc.queryForList("select text_enc from jarvis_souvenirs where user_id = ?",
                String.class, a.id());
        assertThat(sections).doesNotContain("Claire").doesNotContain("ENTOURAGE").contains(".");
        assertThat(texts).hasSize(1);
        assertThat(texts.get(0)).doesNotContain("hospitalise").doesNotContain("Lyon");
    }

    @Test
    void isolationBetweenAccountsAndEraseAll() throws Exception {
        Account a = account("portrait-a");
        Account b = account("portrait-b");
        mockMvc.perform(put("/api/jarvis/portrait").contextPath("/api").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sections\":{\"QUI\":\"Alice\"}}"))
                .andExpect(status().isOk());
        String souvenirA = addSouvenir(a, "Le secret de A");

        mockMvc.perform(get("/api/jarvis/portrait").contextPath("/api").header("Authorization", b.bearer()))
                .andExpect(jsonPath("$.exists").value(false)).andExpect(jsonPath("$.sections[0].text").value(""))
                .andExpect(jsonPath("$.souvenirs.length()").value(0));
        mockMvc.perform(patch("/api/jarvis/portrait/souvenirs/" + souvenirA).contextPath("/api")
                        .header("Authorization", b.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"vole\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/jarvis/portrait/souvenirs/" + souvenirA).contextPath("/api")
                .header("Authorization", b.bearer())).andExpect(status().isNotFound());
        mockMvc.perform(post("/api/jarvis/portrait/souvenirs/" + souvenirA + "/integrate").contextPath("/api")
                        .header("Authorization", b.bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"section\":\"QUI\"}"))
                .andExpect(status().isNotFound());
        // « Tout effacer » de B ne touche pas A.
        mockMvc.perform(delete("/api/jarvis/portrait").contextPath("/api").header("Authorization", b.bearer()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/jarvis/portrait").contextPath("/api").header("Authorization", a.bearer()))
                .andExpect(jsonPath("$.sections[0].text").value("Alice"))
                .andExpect(jsonPath("$.souvenirs.length()").value(1));
        // « Tout effacer » de A : physique.
        mockMvc.perform(delete("/api/jarvis/portrait").contextPath("/api").header("Authorization", a.bearer()))
                .andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("select count(*) from jarvis_portraits where user_id = ?", Integer.class,
                a.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from jarvis_souvenirs where user_id = ?", Integer.class,
                a.id())).isZero();
    }

    @Test
    void accountDeletionPurgesPortraitAndSouvenirs() throws Exception {
        Account a = account("portrait-purge");
        Account keep = account("portrait-keep");
        for (Account x : List.of(a, keep)) {
            mockMvc.perform(put("/api/jarvis/portrait").contextPath("/api").header("Authorization", x.bearer())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"sections\":{\"QUI\":\"x\"}}"))
                    .andExpect(status().isOk());
            addSouvenir(x, "un souvenir");
        }
        allusionService.record(a.id(), "ALLUSION", "le petit", "Léo dort ?", true);
        allusionService.record(keep.id(), "ALLUSION", "le jazz", null, false);
        accountService.deleteAccount(a.id());
        assertThat(jdbc.queryForObject("select count(*) from jarvis_allusions where user_id = ?", Integer.class,
                a.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from jarvis_allusions where user_id = ?", Integer.class,
                keep.id())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from jarvis_portraits where user_id = ?", Integer.class,
                a.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from jarvis_souvenirs where user_id = ?", Integer.class,
                a.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from jarvis_souvenirs where user_id = ?", Integer.class,
                keep.id())).isEqualTo(1);
    }
}
