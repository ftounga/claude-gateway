package fr.claudegateway.atelier.actions;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

/**
 * La vue par interlocuteur de bout en bout (F-180 / SF-180-05) : Rémi et ses 11 attentes, la plus ancienne
 * d'abord ; Bob ne voit rien.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InterlocuteurBoardApiIntegrationTest extends AttentesIntegrationSupport {

    @Autowired
    private InterlocuteurRepository interlocuteurRepository;

    private String alice;
    private String bob;
    private String terminal;

    @BeforeEach
    void setUp() throws Exception {
        actionRepository.deleteAll();
        interlocuteurRepository.deleteAll();
        workspaceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        alice = tokenFor("alice-board@ex.com");
        bob = tokenFor("bob-board@ex.com");
        terminal = createWorkspace(alice, "data-platform");
        attachToHost(terminal, UUID.randomUUID());
    }

    @Test
    @DisplayName("Rémi : 11 attentes, la plus ancienne d'abord, en tête de la vue")
    void eleven() throws Exception {
        for (int i = 0; i < 11; i++) {
            createAction(alice, terminal, "{\"description\":\"Attente " + i + "\",\"person\":\"Rémi\"}");
        }
        createAction(alice, terminal, "{\"description\":\"Autre\",\"person\":\"Karim\"}");

        perform(alice, get("/api/workspaces/" + terminal + "/interlocuteurs/board"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.people", hasSize(2)))
                .andExpect(jsonPath("$.people[0].name", is("Rémi")))
                .andExpect(jsonPath("$.people[0].openCount", is(11)))
                .andExpect(jsonPath("$.people[0].open[0].description", is("Attente 0")))
                .andExpect(jsonPath("$.people[0].subjects[0]", is("data-platform")));

        perform(bob, get("/api/workspaces/" + terminal + "/interlocuteurs/board")).andExpect(status().isNotFound());
    }
}
