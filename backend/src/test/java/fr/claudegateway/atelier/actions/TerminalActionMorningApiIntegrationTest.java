package fr.claudegateway.atelier.actions;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

/**
 * Échéances et point du matin de bout en bout (F-180 / SF-180-06) : saisie, effacement, 400 sur une date
 * illisible, point du matin, et rien pour Bob.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TerminalActionMorningApiIntegrationTest extends AttentesIntegrationSupport {

    private String alice;
    private String bob;
    private String terminal;

    @BeforeEach
    void setUp() throws Exception {
        actionRepository.deleteAll();
        workspaceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        alice = tokenFor("alice-morning@ex.com");
        bob = tokenFor("bob-morning@ex.com");
        terminal = createWorkspace(alice, "data-platform");
    }

    @Test
    @DisplayName("échéance dépassée : au point du matin ; effacée : plus rien ; date illisible : 400")
    void dueAndMorning() throws Exception {
        LocalDate yesterday = LocalDate.now(ZoneId.of("Europe/Paris")).minusDays(1);
        String id = createAction(alice, terminal,
                "{\"description\":\"Valider le budget\",\"dueAt\":\"" + yesterday + "\"}");

        perform(alice, get("/api/workspaces/" + terminal + "/actions/morning"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overdue", hasSize(1)))
                .andExpect(jsonPath("$.overdue[0].dueState", is("OVERDUE")));

        perform(alice, patch("/api/workspaces/" + terminal + "/actions/" + id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"dueAt\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dueState", is("NONE")));
        perform(alice, get("/api/workspaces/" + terminal + "/actions/morning"))
                .andExpect(jsonPath("$.overdue", hasSize(0)));

        perform(alice, patch("/api/workspaces/" + terminal + "/actions/" + id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"dueAt\":\"vendredi\"}"))
                .andExpect(status().isBadRequest());

        perform(bob, get("/api/workspaces/" + terminal + "/actions/morning")).andExpect(status().isNotFound());
    }
}
