package fr.claudegateway.atelier.actions;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Nature et qui agit, de bout en bout (F-180 / SF-180-02) : création qualifiée, valeur inconnue refusée,
 * reprise proposée puis appliquée — et rien pour Bob.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TerminalActionQualifyApiIntegrationTest extends AttentesIntegrationSupport {

    private String alice;
    private String bob;
    private String terminal;

    @BeforeEach
    void setUp() throws Exception {
        actionRepository.deleteAll();
        workspaceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        alice = tokenFor("alice-nature@ex.com");
        bob = tokenFor("bob-nature@ex.com");
        terminal = createWorkspace(alice, "data-platform");
    }

    @Test
    @DisplayName("création qualifiée ; valeur inconnue : 400")
    void createQualified() throws Exception {
        postJson(alice, "/api/workspaces/" + terminal + "/actions",
                "{\"description\":\"Demander le VPN\",\"categorie\":\"ACCES\",\"qui\":\"CLIENT\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categorie", is("ACCES")))
                .andExpect(jsonPath("$.qui", is("CLIENT")));
        postJson(alice, "/api/workspaces/" + terminal + "/actions",
                "{\"description\":\"x\",\"categorie\":\"NAWAK\"}")
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("reprise : proposée avec une nature devinée, appliquée sur validation ; Bob n'y touche pas")
    void qualifyFlow() throws Exception {
        String id = createAction(alice, terminal, "{\"description\":\"Demander l'accès VPN à Karim\",\"person\":\"Karim\"}");

        perform(alice, get("/api/terminal-actions/qualify"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].suggestedCategorie", is("ACCES")))
                .andExpect(jsonPath("$[0].suggestedQui", is("CLIENT")));
        perform(bob, get("/api/terminal-actions/qualify")).andExpect(jsonPath("$", hasSize(0)));

        postJson(bob, "/api/terminal-actions/qualify",
                "{\"decisions\":[{\"id\":\"" + id + "\",\"categorie\":\"INFO\",\"qui\":\"TIERS\"}]}")
                .andExpect(jsonPath("$.applied", is(0)))
                .andExpect(jsonPath("$.ignored", is(1)));

        postJson(alice, "/api/terminal-actions/qualify",
                "{\"decisions\":[{\"id\":\"" + id + "\",\"categorie\":\"ACCES\",\"qui\":\"CLIENT\"}]}")
                .andExpect(jsonPath("$.applied", is(1)));
        perform(alice, get("/api/terminal-actions/qualify")).andExpect(jsonPath("$", hasSize(0)));
        perform(alice, get("/api/workspaces/" + terminal + "/actions/board"))
                .andExpect(jsonPath("$.here[0].categorie", is("ACCES")));
    }
}
