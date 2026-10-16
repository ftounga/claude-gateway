package fr.claudegateway.atelier.actions;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import com.jayway.jsonpath.JsonPath;

/**
 * Le registre des accès de bout en bout (F-180 / SF-180-04) : une attente ACCES faite est proposée,
 * l'ajout la retire des propositions, un secret est refusé, et Bob ne voit rien.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccesRegistryApiIntegrationTest extends AttentesIntegrationSupport {

    @Autowired
    private AccesObtenuRepository accesRepository;
    @Autowired
    private InterlocuteurRepository interlocuteurRepository;

    private String alice;
    private String bob;
    private String terminal;

    @BeforeEach
    void setUp() throws Exception {
        actionRepository.deleteAll();
        accesRepository.deleteAll();
        interlocuteurRepository.deleteAll();
        workspaceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        alice = tokenFor("alice-acces@ex.com");
        bob = tokenFor("bob-acces@ex.com");
        terminal = createWorkspace(alice, "data-platform");
        attachToHost(terminal, UUID.randomUUID());
    }

    @Test
    @DisplayName("Fait → proposé ; ajouté → plus proposé ; listé ; modifié ; supprimé")
    void lifecycle() throws Exception {
        String actionId = createAction(alice, terminal,
                "{\"description\":\"Demander l'accès VPN à Karim\",\"person\":\"Karim\",\"categorie\":\"ACCES\"}");
        postJson(alice, "/api/workspaces/" + terminal + "/actions/" + actionId + "/close", "{\"reason\":\"ouvert\"}")
                .andExpect(status().isOk());

        perform(alice, get("/api/workspaces/" + terminal + "/acces/proposals"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].system", is("VPN")))
                .andExpect(jsonPath("$[0].grantedBy", is("Karim")));

        String body = postJson(alice, "/api/workspaces/" + terminal + "/acces",
                "{\"actionId\":\"" + actionId + "\",\"system\":\"VPN\",\"account\":\"k.dupont\","
                        + "\"grantedBy\":\"Karim\",\"expiresAt\":\"2099-12-31\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.system", is("VPN")))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        perform(alice, get("/api/workspaces/" + terminal + "/acces/proposals")).andExpect(jsonPath("$", hasSize(0)));
        perform(alice, get("/api/workspaces/" + terminal + "/acces"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].account", is("k.dupont")));
        perform(alice, patch("/api/workspaces/" + terminal + "/acces/" + id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"karim.d\"}"))
                .andExpect(jsonPath("$.account", is("karim.d")));
        perform(alice, delete("/api/workspaces/" + terminal + "/acces/" + id)).andExpect(status().isOk());
        perform(alice, get("/api/workspaces/" + terminal + "/acces")).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("un secret est refusé : 400, rien d'écrit")
    void secretRefused() throws Exception {
        postJson(alice, "/api/workspaces/" + terminal + "/acces",
                "{\"system\":\"VPN\",\"account\":\"k.dupont\",\"note\":\"mot de passe : Azerty123!\"}")
                .andExpect(status().isBadRequest());
        perform(alice, get("/api/workspaces/" + terminal + "/acces")).andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("isolation : Bob ne lit, ne modifie ni ne supprime le registre d'Alice")
    void isolation() throws Exception {
        String body = postJson(alice, "/api/workspaces/" + terminal + "/acces", "{\"system\":\"VPN\"}")
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.id");

        perform(bob, get("/api/workspaces/" + terminal + "/acces")).andExpect(status().isNotFound());
        String bobTerminal = createWorkspace(bob, "bob");
        perform(bob, patch("/api/workspaces/" + bobTerminal + "/acces/" + id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"account\":\"x\"}"))
                .andExpect(status().isNotFound());
        perform(bob, delete("/api/workspaces/" + bobTerminal + "/acces/" + id)).andExpect(status().isNotFound());
        perform(alice, get("/api/workspaces/" + terminal + "/acces")).andExpect(jsonPath("$", hasSize(1)));
    }
}
