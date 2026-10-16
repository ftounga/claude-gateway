package fr.claudegateway.atelier.actions;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
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
 * Les interlocuteurs de bout en bout (F-180 / SF-180-03) : l'attente crée l'interlocuteur, les graphies se
 * rejoignent, la fusion reporte les attentes, et Bob ne voit rien.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InterlocuteurApiIntegrationTest extends AttentesIntegrationSupport {

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
        alice = tokenFor("alice-interloc@ex.com");
        bob = tokenFor("bob-interloc@ex.com");
        terminal = createWorkspace(alice, "data-platform");
        attachToHost(terminal, UUID.randomUUID());
    }

    @Test
    @DisplayName("graphies réunies, fusion proposée et appliquée, attentes reportées")
    void lifecycle() throws Exception {
        createAction(alice, terminal, "{\"description\":\"Demander le VPN\",\"person\":\"Rémi\"}");
        createAction(alice, terminal, "{\"description\":\"Demander le proxy\",\"person\":\"remi\"}");
        createAction(alice, terminal, "{\"description\":\"Valider le flux\",\"person\":\"Rémi Dupont\"}");

        String list = perform(alice, get("/api/workspaces/" + terminal + "/interlocuteurs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        String remi = JsonPath.read(list, "$[?(@.displayName == 'Rémi')].id").toString().replaceAll("[\\[\\]\"]", "");
        String full = JsonPath.read(list, "$[?(@.displayName == 'Rémi Dupont')].id").toString().replaceAll("[\\[\\]\"]", "");

        perform(alice, get("/api/workspaces/" + terminal + "/interlocuteurs/merge-suggestions"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].reason", is("PREFIX")));

        postJson(alice, "/api/workspaces/" + terminal + "/interlocuteurs/" + remi + "/merge", "{\"intoId\":\"" + full + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName", is("Rémi Dupont")));

        perform(alice, get("/api/workspaces/" + terminal + "/interlocuteurs"))
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].openCount", is(3)));
        perform(alice, get("/api/workspaces/" + terminal + "/actions/board"))
                .andExpect(jsonPath("$.here[0].interlocuteurName", is("Rémi Dupont")));

        perform(alice, patch("/api/workspaces/" + terminal + "/interlocuteurs/" + full)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"Réseau\",\"team\":\"Infra\"}"))
                .andExpect(jsonPath("$.role", is("Réseau")));
    }

    @Test
    @DisplayName("isolation : Bob ne lit, ne modifie ni ne fusionne rien d'Alice")
    void isolation() throws Exception {
        createAction(alice, terminal, "{\"description\":\"Demander le VPN\",\"person\":\"Rémi\"}");
        String list = perform(alice, get("/api/workspaces/" + terminal + "/interlocuteurs"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(list, "$[0].id");

        perform(bob, get("/api/workspaces/" + terminal + "/interlocuteurs")).andExpect(status().isNotFound());
        String bobTerminal = createWorkspace(bob, "bob");
        attachToHost(bobTerminal, UUID.randomUUID());
        perform(bob, patch("/api/workspaces/" + bobTerminal + "/interlocuteurs/" + id)
                .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"x\"}"))
                .andExpect(status().isNotFound());
        postJson(bob, "/api/workspaces/" + bobTerminal + "/interlocuteurs/" + id + "/merge",
                "{\"intoId\":\"" + UUID.randomUUID() + "\"}").andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("terminal hébergé : aucune liste")
    void hosted() throws Exception {
        String hosted = createWorkspace(alice, "heberge");
        createAction(alice, hosted, "{\"description\":\"Demander le VPN\",\"person\":\"Rémi\"}");
        perform(alice, get("/api/workspaces/" + hosted + "/interlocuteurs")).andExpect(jsonPath("$", hasSize(0)));
    }
}
