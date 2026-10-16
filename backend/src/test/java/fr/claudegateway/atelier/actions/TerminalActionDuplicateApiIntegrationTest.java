package fr.claudegateway.atelier.actions;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Les doublons de bout en bout (F-180 / SF-180-01) : deux attentes du même poste nées dans deux
 * terminaux sont proposées ; la fusion annule la plus récente ; « pas pareil » ne repropose plus ; Bob
 * ne voit ni ne fusionne rien d'Alice.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TerminalActionDuplicateApiIntegrationTest extends AttentesIntegrationSupport {

    private String alice;
    private String bob;
    private String terminalA;
    private String terminalB;

    @BeforeEach
    void setUp() throws Exception {
        actionRepository.deleteAll();
        workspaceRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();
        alice = tokenFor("alice-dup@ex.com");
        bob = tokenFor("bob-dup@ex.com");
        terminalA = createWorkspace(alice, "data-platform");
        terminalB = createWorkspace(alice, "connecteur");
        UUID host = UUID.randomUUID();
        attachToHost(terminalA, host);
        attachToHost(terminalB, host);
    }

    @Test
    @DisplayName("proposée, fusionnée : la plus récente passe Annulé, fusionnée dans la plus ancienne")
    void proposeAndMerge() throws Exception {
        String older = createAction(alice, terminalA, "{\"description\":\"Demander l'accès VPN Karim\"}");
        String newer = createAction(alice, terminalB, "{\"description\":\"Obtenir accès VPN pour Karim\"}");

        perform(alice, get("/api/workspaces/" + terminalA + "/actions/duplicates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].keep.id", is(older)))
                .andExpect(jsonPath("$[0].duplicate.id", is(newer)))
                .andExpect(jsonPath("$[0].reason", is("WORDS")));

        postJson(alice, "/api/workspaces/" + terminalA + "/actions/" + newer + "/merge", "{\"intoId\":\"" + older + "\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ANNULE")))
                .andExpect(jsonPath("$.closedReason", startsWith("Fusionnée avec")));

        perform(alice, get("/api/workspaces/" + terminalA + "/actions/duplicates"))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("« ce n'est pas pareil » : plus proposée")
    void notDuplicate() throws Exception {
        createAction(alice, terminalA, "{\"description\":\"Demander l'accès VPN Karim\"}");
        String newer = createAction(alice, terminalB, "{\"description\":\"Obtenir accès VPN pour Karim\"}");

        postJson(alice, "/api/workspaces/" + terminalA + "/actions/" + newer + "/not-duplicate", null)
                .andExpect(status().isOk());

        perform(alice, get("/api/workspaces/" + terminalA + "/actions/duplicates"))
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    @DisplayName("isolation : Bob ne voit ni ne fusionne les attentes d'Alice (404)")
    void isolation() throws Exception {
        String older = createAction(alice, terminalA, "{\"description\":\"Demander l'accès VPN Karim\"}");
        String newer = createAction(alice, terminalB, "{\"description\":\"Obtenir accès VPN pour Karim\"}");

        perform(bob, get("/api/workspaces/" + terminalA + "/actions/duplicates"))
                .andExpect(status().isNotFound());
        postJson(bob, "/api/workspaces/" + terminalA + "/actions/" + newer + "/merge", "{\"intoId\":\"" + older + "\"}")
                .andExpect(status().isNotFound());
        String bobTerminal = createWorkspace(bob, "bob");
        postJson(bob, "/api/workspaces/" + bobTerminal + "/actions/" + newer + "/merge", "{\"intoId\":\"" + older + "\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("fusion sans attente gardée : 400")
    void mergeWithoutInto() throws Exception {
        String a = createAction(alice, terminalA, "{\"description\":\"Demander l'accès VPN Karim\"}");
        postJson(alice, "/api/workspaces/" + terminalA + "/actions/" + a + "/merge", "{}")
                .andExpect(status().isBadRequest());
    }
}
