package fr.claudegateway.atelier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fr.claudegateway.atelier.agent.AtelierAgentListener;
import fr.claudegateway.atelier.agent.AtelierAgentProperties;
import fr.claudegateway.atelier.agent.AtelierSessionResult;
import fr.claudegateway.atelier.agent.AtelierSessionService;
import fr.claudegateway.auth.CurrentUser;

/**
 * Tests du contrôleur SSE d'exécution Phase 2 (F-28 / SF-28-10). Montés en {@code standaloneSetup} avec
 * un {@link AtelierSessionService} mocké et un exécuteur synchrone ({@code Runnable::run}) : le relais
 * s'exécute au retour du contrôleur, rendant le corps du flux lisible directement. Vérifie que le
 * gating (accès) et le flag sont résolus sur le thread de requête puis émis <b>dans le flux</b>
 * (jamais un 406/JSON), et que les événements {@code agent}/{@code done} sont relayés.
 */
class AtelierAgentControllerTest {

    private static final UUID USER = UUID.randomUUID();
    private static final UUID WORKSPACE = UUID.randomUUID();

    private AtelierSessionService sessionService;
    private AtelierAccessService access;
    private CurrentUser currentUser;

    private MockMvc mockMvc(AtelierAgentProperties properties) {
        AtelierAgentController controller = new AtelierAgentController(
                sessionService, access, properties, currentUser, Runnable::run);
        return MockMvcBuilders.standaloneSetup(controller).build();
    }

    private AtelierAgentProperties props(boolean enabled) {
        return new AtelierAgentProperties(enabled, null, null, null, null, null, null, null, null, null,
                null, null, null, false, null, null);
    }

    @BeforeEach
    void setUp() {
        sessionService = Mockito.mock(AtelierSessionService.class);
        access = Mockito.mock(AtelierAccessService.class);
        currentUser = Mockito.mock(CurrentUser.class);
        when(currentUser.requireId()).thenReturn(USER);
    }

    @Test
    void streamRelaysAgentThenDoneAsSse() throws Exception {
        when(access.hasAccess()).thenReturn(true);
        // Le service mocké invoque le listener (étapes) puis renvoie le résultat final.
        when(sessionService.runTaskStreaming(eq(USER), eq(WORKSPACE), any(), any())).thenAnswer(inv -> {
            AtelierAgentListener listener = inv.getArgument(3);
            listener.onStatus("running");
            listener.onAgentText("J'ai lu le projet.");
            listener.onAction("bash", "ls -la");
            listener.onStatus("idle");
            return new AtelierSessionResult("Terminé.", List.of("src/a.txt"));
        });

        var result = mockMvc(props(true)).perform(post("/workspaces/" + WORKSPACE + "/agent/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"lis le projet\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("event:status")
                .contains("event:agent")
                .contains("J'ai lu le projet.")
                .contains("event:action")
                .contains("bash")
                .contains("event:done")
                .contains("Terminé.")
                .contains("src/a.txt");
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentType())
                .contains("text/event-stream");
    }

    @Test
    void streamRelaysToolOutputAsActionResultWithoutChangingExistingEvents() throws Exception {
        // F-30 SF-30-01 : la sortie des commandes est relayée dans un événement ADDITIF.
        // Les événements existants doivent rester inchangés (non-régression explicite).
        when(access.hasAccess()).thenReturn(true);
        when(sessionService.runTaskStreaming(eq(USER), eq(WORKSPACE), any(), any())).thenAnswer(inv -> {
            AtelierAgentListener listener = inv.getArgument(3);
            listener.onStatus("running");
            listener.onAction("bash", "npm test");
            listener.onActionResult("bash", "tu_1", "12 passing", false);
            listener.onAction("bash", "npm run build");
            listener.onActionResult("bash", "tu_2", "command not found", true);
            listener.onStatus("idle");
            return new AtelierSessionResult("Terminé.", List.of());
        });

        var result = mockMvc(props(true)).perform(post("/workspaces/" + WORKSPACE + "/agent/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"lance les tests\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("event:action_result")
                .contains("12 passing")
                .contains("tu_1")
                .contains("command not found")
                .contains("\"error\":true")
                // Non-régression : les événements préexistants sont toujours émis à l'identique.
                .contains("event:status")
                .contains("event:action")
                .contains("npm test")
                .contains("event:done");
    }

    @Test
    void doneCarriesTurnUsageAsAdditiveFields() throws Exception {
        // F-30 SF-30-05 : la consommation du tour voyage dans `done`, en champs ADDITIFS.
        when(access.hasAccess()).thenReturn(true);
        when(sessionService.runTaskStreaming(eq(USER), eq(WORKSPACE), any(), any()))
                .thenReturn(new AtelierSessionResult("Terminé.", List.of(), 1_200L, 300L, 42L));

        var result = mockMvc(props(true)).perform(post("/workspaces/" + WORKSPACE + "/agent/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"go\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("event:done")
                .contains("\"inputTokens\":1200")
                .contains("\"outputTokens\":300")
                .contains("\"activeSeconds\":42")
                // Non-régression : les champs préexistants de `done` sont intacts.
                .contains("\"reply\":\"Terminé.\"")
                .contains("\"changedFiles\":[]");
    }

    @Test
    void streamWithoutAccessEmitsForbiddenInStreamNotHttp406() throws Exception {
        when(access.hasAccess()).thenReturn(false);

        var result = mockMvc(props(true)).perform(post("/workspaces/" + WORKSPACE + "/agent/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"salut\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .contains("event:error")
                .contains("forbidden");
        // Aucun run n'est lancé si l'accès est refusé.
        verify(sessionService, never()).runTaskStreaming(any(), any(), any(), any());
    }

    @Test
    void streamWithFlagOffEmitsAgentDisabledWithoutCallingService() throws Exception {
        when(access.hasAccess()).thenReturn(true);

        var result = mockMvc(props(false)).perform(post("/workspaces/" + WORKSPACE + "/agent/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"salut\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        org.assertj.core.api.Assertions.assertThat(result.getResponse().getStatus()).isEqualTo(200);
        org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .contains("event:error")
                .contains("agent_disabled");
        // Flag off => aucun appel Anthropic (le service n'est jamais sollicité).
        verify(sessionService, never()).runTaskStreaming(any(), any(), any(), any());
    }
    @Test
    void exhaustedProviderCreditIsRelayedAsItsOwnErrorCode() throws Exception {
        // F-30 SF-30-08 : distinct de `provider_error` — réessayer ne peut pas aboutir.
        when(access.hasAccess()).thenReturn(true);
        when(sessionService.runTaskStreaming(eq(USER), eq(WORKSPACE), any(), any()))
                .thenThrow(new fr.claudegateway.atelier.agent.AgentCreditExhaustedException("épuisé", null));

        var result = mockMvc(props(true)).perform(post("/workspaces/" + WORKSPACE + "/agent/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"go\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(body).contains("credit_exhausted");
    }

    @Test
    void otherProviderFailuresKeepTheirExistingCode() throws Exception {
        // Non-régression : une panne ordinaire reste `provider_error`.
        when(access.hasAccess()).thenReturn(true);
        when(sessionService.runTaskStreaming(eq(USER), eq(WORKSPACE), any(), any()))
                .thenThrow(new fr.claudegateway.atelier.agent.AgentProviderException("boom"));

        var result = mockMvc(props(true)).perform(post("/workspaces/" + WORKSPACE + "/agent/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"go\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("provider_error")
                .doesNotContain("credit_exhausted");
    }
    // -------------------------------------- F-32 / SF-32-01 : interruption d'un run

    /** Contrôleur monté avec l'advice d'erreurs global : les codes JSON sont ceux de production. */
    private MockMvc mockMvcWithErrorHandling() {
        AtelierAgentController controller = new AtelierAgentController(
                sessionService, access, props(true), currentUser, Runnable::run);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new fr.claudegateway.shared.error.GlobalExceptionHandler())
                .build();
    }

    @Test
    void interruptRelaysTheRequestAndAnswersNoContent() throws Exception {
        mockMvcWithErrorHandling().perform(post("/workspaces/" + WORKSPACE + "/agent/interrupt"))
                .andExpect(status().isNoContent());

        verify(sessionService).interruptSession(USER, WORKSPACE);
    }

    @Test
    void interruptOnAWorkspaceOfAnotherUserIsNotFound() throws Exception {
        // Isolation : le service lève avant tout appel fournisseur, l'API répond 404 comme ailleurs.
        Mockito.doThrow(new WorkspaceNotFoundException("inconnu"))
                .when(sessionService).interruptSession(USER, WORKSPACE);

        mockMvcWithErrorHandling().perform(post("/workspaces/" + WORKSPACE + "/agent/interrupt"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void interruptWithoutAnyRunningSessionIsAConflict() throws Exception {
        Mockito.doThrow(new fr.claudegateway.atelier.agent.NoActiveSessionException("rien à interrompre"))
                .when(sessionService).interruptSession(USER, WORKSPACE);

        mockMvcWithErrorHandling().perform(post("/workspaces/" + WORKSPACE + "/agent/interrupt"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("no_active_session"));
    }

    @Test
    void interruptRefusedByTheProviderIsABadGateway() throws Exception {
        // La panne est chez le fournisseur, pas dans la Gateway : 502, et aucun détail technique.
        Mockito.doThrow(new fr.claudegateway.atelier.agent.AgentProviderException("boom"))
                .when(sessionService).interruptSession(USER, WORKSPACE);

        mockMvcWithErrorHandling().perform(post("/workspaces/" + WORKSPACE + "/agent/interrupt"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("provider_error"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("boom"))));
    }

    @Test
    void doneCarriesTheInterruptedFlagAsAnAdditiveField() throws Exception {
        // Le tour interrompu n'est pas une erreur : il se clôt par `done`, marqué comme interrompu.
        when(access.hasAccess()).thenReturn(true);
        when(sessionService.runTaskStreaming(eq(USER), eq(WORKSPACE), any(), any()))
                .thenReturn(new AtelierSessionResult("Arrêté.", List.of(), 900L, 100L, 42L, true));

        var result = mockMvc(props(true)).perform(post("/workspaces/" + WORKSPACE + "/agent/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"go\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("event:done")
                .contains("\"interrupted\":true")
                .doesNotContain("event:error");
    }

    @Test
    void doneOfANominalRunReportsNoInterruption() throws Exception {
        when(access.hasAccess()).thenReturn(true);
        when(sessionService.runTaskStreaming(eq(USER), eq(WORKSPACE), any(), any()))
                .thenReturn(new AtelierSessionResult("Terminé.", List.of()));

        var result = mockMvc(props(true)).perform(post("/workspaces/" + WORKSPACE + "/agent/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"go\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(body).contains("\"interrupted\":false");
    }

    // ---------------------------- F-33 / SF-33-01 : demander avant d'exécuter

    @Test
    void enablingConfirmationSavesTheOptionAndSaysItAppliesNow() throws Exception {
        when(sessionService.setAskBeforeBash(USER, WORKSPACE, true))
                .thenReturn(new AtelierSessionService.AgentConfirmationState(true, true));

        mockMvcWithErrorHandling().perform(put("/workspaces/" + WORKSPACE + "/agent/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.appliesToCurrentSession").value(true));

        verify(sessionService).setAskBeforeBash(USER, WORKSPACE, true);
    }

    @Test
    void enablingConfirmationWithAnOpenSessionSaysItDoesNotApplyYet() throws Exception {
        when(sessionService.setAskBeforeBash(USER, WORKSPACE, true))
                .thenReturn(new AtelierSessionService.AgentConfirmationState(true, false));

        mockMvcWithErrorHandling().perform(put("/workspaces/" + WORKSPACE + "/agent/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.appliesToCurrentSession").value(false));
    }

    @Test
    void confirmationOnAWorkspaceOfAnotherUserIsNotFound() throws Exception {
        Mockito.doThrow(new WorkspaceNotFoundException("Workspace introuvable"))
                .when(sessionService).setAskBeforeBash(USER, WORKSPACE, true);

        mockMvcWithErrorHandling().perform(put("/workspaces/" + WORKSPACE + "/agent/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void confirmationWithoutAnExplicitValueIsRejected() throws Exception {
        // Réglage de sécurité : on ne devine pas l'intention d'un corps vide.
        mockMvcWithErrorHandling().perform(put("/workspaces/" + WORKSPACE + "/agent/confirmation")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        verify(sessionService, never()).setAskBeforeBash(any(), any(), Mockito.anyBoolean());
    }

    // ---------------------------- F-33 / SF-33-02 : réponse à une demande d'autorisation

    @Test
    void confirmAllowRelaysTheDecisionAndAnswersNoContent() throws Exception {
        mockMvcWithErrorHandling().perform(post("/workspaces/" + WORKSPACE + "/agent/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolUseId\":\"sevt_1\",\"decision\":\"allow\"}"))
                .andExpect(status().isNoContent());

        verify(sessionService).confirmToolUse(USER, WORKSPACE, "sevt_1", true, null);
    }

    @Test
    void confirmDenyCarriesTheReasonToTheAgent() throws Exception {
        mockMvcWithErrorHandling().perform(post("/workspaces/" + WORKSPACE + "/agent/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolUseId\":\"sevt_1\",\"decision\":\"deny\",\"reason\":\"trop risqué\"}"))
                .andExpect(status().isNoContent());

        verify(sessionService).confirmToolUse(USER, WORKSPACE, "sevt_1", false, "trop risqué");
    }

    @Test
    void confirmOnAWorkspaceOfAnotherUserIsNotFound() throws Exception {
        Mockito.doThrow(new WorkspaceNotFoundException("Workspace introuvable"))
                .when(sessionService).confirmToolUse(any(), any(), any(), Mockito.anyBoolean(), any());

        mockMvcWithErrorHandling().perform(post("/workspaces/" + WORKSPACE + "/agent/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolUseId\":\"sevt_1\",\"decision\":\"allow\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void confirmWithoutAnyRunningSessionIsAConflict() throws Exception {
        Mockito.doThrow(new fr.claudegateway.atelier.agent.NoActiveSessionException("rien à autoriser"))
                .when(sessionService).confirmToolUse(any(), any(), any(), Mockito.anyBoolean(), any());

        mockMvcWithErrorHandling().perform(post("/workspaces/" + WORKSPACE + "/agent/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolUseId\":\"sevt_1\",\"decision\":\"allow\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("no_active_session"));
    }

    @Test
    void confirmRefusedByTheProviderIsABadGateway() throws Exception {
        // Demande inconnue ou déjà tranchée : la Gateway n'y peut rien, et ne masque pas l'échec.
        Mockito.doThrow(new fr.claudegateway.atelier.agent.AgentProviderException("boom"))
                .when(sessionService).confirmToolUse(any(), any(), any(), Mockito.anyBoolean(), any());

        mockMvcWithErrorHandling().perform(post("/workspaces/" + WORKSPACE + "/agent/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolUseId\":\"sevt_1\",\"decision\":\"allow\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("provider_error"));
    }

    @Test
    void confirmWithAnUnknownDecisionIsRejected() throws Exception {
        mockMvcWithErrorHandling().perform(post("/workspaces/" + WORKSPACE + "/agent/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"toolUseId\":\"sevt_1\",\"decision\":\"peut-être\"}"))
                .andExpect(status().isBadRequest());

        verify(sessionService, never()).confirmToolUse(any(), any(), any(), Mockito.anyBoolean(), any());
    }

    @Test
    void streamRelaysConfirmationRequestAndResolutionAsAdditiveEvents() throws Exception {
        when(access.hasAccess()).thenReturn(true);
        when(sessionService.runTaskStreaming(eq(USER), eq(WORKSPACE), any(), any())).thenAnswer(inv -> {
            AtelierAgentListener listener = inv.getArgument(3);
            listener.onConfirmationRequest("bash", "sevt_1", "rm -rf build");
            listener.onConfirmationResolved("sevt_1", "deny");
            return new AtelierSessionResult("Compris.", List.of());
        });

        var result = mockMvc(props(true)).perform(post("/workspaces/" + WORKSPACE + "/agent/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"message\":\"nettoie le projet\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(body)
                .contains("event:confirm_request")
                .contains("\"toolUseId\":\"sevt_1\"")
                .contains("rm -rf build")
                .contains("event:confirm_resolved")
                .contains("\"decision\":\"deny\"")
                .doesNotContain("event:error");
    }
}
