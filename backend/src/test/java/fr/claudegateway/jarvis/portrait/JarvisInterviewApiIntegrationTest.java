package fr.claudegateway.jarvis.portrait;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * F-241 / SF-241-02 — l'entretien : brouillon rangé par un appel bouchonné, <b>rien n'est écrit</b> avant la
 * validation ; cadence de la proposition ; le brouillon de A n'est jamais rendu à B ; validation datée.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class JarvisInterviewApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private JwtService jwtService;
    @Autowired private JdbcTemplate jdbc;
    @MockitoBean private AiAgentProvider agentProvider;

    private record Account(UUID id, String bearer) {
    }

    private Account account(String prefix) {
        User user = userRepository.save(User.builder().email(prefix + "-" + UUID.randomUUID() + "@ex.com")
                .emailVerified(true).provider(AuthProvider.LOCAL).role(UserRole.USER).build());
        return new Account(user.getId(), "Bearer " + jwtService.generateToken(user));
    }

    private static final String ANSWERS = """
            {"answers":[{"question":"Quel est votre métier, votre rôle aujourd'hui ?","answer":"Chef de projet"},
                        {"question":"Qui partage votre vie ? (vous pouvez passer)","answer":""},
                        {"question":"Y a-t-il des sujets que je ne dois jamais aborder ?","answer":"Le divorce"}]}""";

    @Test
    void draftIsRangedButNothingIsWrittenUntilValidation() throws Exception {
        Account a = account("interview-a");
        List<AgentTurnRequest> seen = new ArrayList<>();
        when(agentProvider.nextTurn(any(AgentTurnRequest.class))).thenAnswer(inv -> {
            seen.add(inv.getArgument(0));
            return new AgentTurn("", List.of(new AgentToolCall("t1", JarvisInterviewService.TOOL,
                    new ObjectMapper().readTree("{\"sections\":{\"QUI\":\"Chef de projet\",\"INTERDITS\":\"Le divorce\"},"
                            + "\"souvenirs\":[{\"texte\":\"Naissance prévue en mars\",\"sensible\":true}]}"))),
                    true, 200, 60);
        });
        mockMvc.perform(post("/api/jarvis/portrait/interview/draft").contextPath("/api")
                        .header("Authorization", a.bearer()).contentType(MediaType.APPLICATION_JSON).content(ANSWERS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.QUI").value("Chef de projet"))
                .andExpect(jsonPath("$.sections.INTERDITS").value("Le divorce"))
                .andExpect(jsonPath("$.souvenirs[0].source").value("ENTRETIEN"));
        assertThat(seen).hasSize(1);
        // « Je passe » : la réponse vide n'est même pas envoyée.
        assertThat(seen.get(0).messages().toString()).doesNotContain("Qui partage votre vie");
        assertThat(jdbc.queryForObject("select count(*) from jarvis_portraits where user_id = ?", Integer.class,
                a.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from jarvis_souvenirs where user_id = ?", Integer.class,
                a.id())).isZero();

        // Validation : le PO enregistre le brouillon ; le dernier entretien est daté.
        mockMvc.perform(put("/api/jarvis/portrait").contextPath("/api").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sections\":{\"QUI\":\"Chef de projet\"},\"interviewed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lastInterviewAt").isNotEmpty());

        // F-241 / SF-241-06 (D4) — un entretien validé clôt la proposition, même portrait vidé ensuite.
        mockMvc.perform(put("/api/jarvis/portrait").contextPath("/api").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sections\":{\"QUI\":\"\"}}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/jarvis/portrait/interview").contextPath("/api").header("Authorization", a.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.offer").value(false));
    }

    @Test
    void providerDownGivesRawDraftAndOtherAccountNeverSeesThePortrait() throws Exception {
        Account a = account("interview-iso-a");
        Account b = account("interview-iso-b");
        mockMvc.perform(put("/api/jarvis/portrait").contextPath("/api").header("Authorization", a.bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"sections\":{\"QUI\":\"Alice Secrète\"}}"))
                .andExpect(status().isOk());
        List<AgentTurnRequest> seen = new ArrayList<>();
        when(agentProvider.nextTurn(any(AgentTurnRequest.class))).thenAnswer(inv -> {
            seen.add(inv.getArgument(0));
            throw new IllegalStateException("panne");
        });
        mockMvc.perform(post("/api/jarvis/portrait/interview/draft").contextPath("/api")
                        .header("Authorization", b.bearer()).contentType(MediaType.APPLICATION_JSON).content(ANSWERS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sections.QUI").value("Chef de projet"))
                .andExpect(jsonPath("$.sections.INTERDITS").value("Le divorce"));
        assertThat(seen.get(0).messages().toString()).doesNotContain("Alice");
        mockMvc.perform(post("/api/jarvis/portrait/interview/draft").contextPath("/api")
                        .header("Authorization", b.bearer()).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void offerCadence() throws Exception {
        Account a = account("interview-offer");
        mockMvc.perform(get("/api/jarvis/portrait/interview").contextPath("/api").header("Authorization", a.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.offer").value(true));
        mockMvc.perform(post("/api/jarvis/portrait/interview/offered").contextPath("/api")
                        .header("Authorization", a.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.offer").value(false))
                .andExpect(jsonPath("$.offers").value(1));
        mockMvc.perform(get("/api/jarvis/portrait/interview").contextPath("/api")).andExpect(status().isUnauthorized());
    }
}
