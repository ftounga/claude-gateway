package fr.claudegateway.atelier;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import fr.claudegateway.atelier.agent.AgentProviderException;
import fr.claudegateway.atelier.agent.AgentSessionTimeoutException;
import fr.claudegateway.atelier.agent.AtelierAgentDisabledException;
import fr.claudegateway.atelier.agent.AtelierAgentListener;
import fr.claudegateway.atelier.agent.AtelierSessionResult;
import fr.claudegateway.atelier.agent.AtelierSessionService;
import fr.claudegateway.atelier.dto.AtelierAgentRequest;
import fr.claudegateway.auth.CurrentUser;
import jakarta.validation.Valid;

/**
 * Endpoint d'exécution Phase 2 de l'Atelier en streaming (F-28 / SF-28-10, ADR-013). Expose
 * {@code POST /workspaces/{id}/agent/stream} (SSE) : lance un run d'exécution sur une session Managed
 * Agents ({@link AtelierSessionService}) et <b>relaie en direct</b> les étapes (texte de l'agent,
 * usage d'outil, transition d'état) puis la réponse finale + les fichiers modifiés.
 *
 * <p>Réplique le patron SSE de {@code AtelierChatController} : gating (Gold/ADMIN, SF-28-06) et flag
 * Phase 2 <b>résolus sur le thread de requête</b> (le pool SSE n'hérite pas du SecurityContext),
 * passés en booléens au relais ; toutes les erreurs (pré-vol et exécution) sont émises <b>dans le
 * flux</b> (événement {@code error}), jamais via l'{@code @ExceptionHandler} JSON (qui produirait un
 * 406 sur cet endpoint SSE). La session est <b>toujours terminée</b> (coût runtime borné) via le
 * {@code finally} de {@link AtelierSessionService}.</p>
 */
@RestController
@RequestMapping("/workspaces/{id}/agent")
public class AtelierAgentController {

    private static final Logger log = LoggerFactory.getLogger(AtelierAgentController.class);

    /** Durée de vie max d'un flux SSE (garde-fou ; un run nominal se clôt bien avant). */
    private static final long STREAM_TIMEOUT_MS = 300_000L;

    private final AtelierSessionService sessionService;
    private final AtelierAccessService atelierAccess;
    private final fr.claudegateway.atelier.agent.AtelierAgentProperties properties;
    private final CurrentUser currentUser;
    private final Executor chatStreamExecutor;

    public AtelierAgentController(AtelierSessionService sessionService, AtelierAccessService atelierAccess,
            fr.claudegateway.atelier.agent.AtelierAgentProperties properties, CurrentUser currentUser,
            @Qualifier("chatStreamExecutor") Executor chatStreamExecutor) {
        this.sessionService = sessionService;
        this.atelierAccess = atelierAccess;
        this.properties = properties;
        this.currentUser = currentUser;
        this.chatStreamExecutor = chatStreamExecutor;
    }

    /**
     * Lance un run d'exécution Phase 2 et relaie les étapes en SSE. Événements émis : {@code agent}
     * (texte), {@code action} (outil), {@code status} (état), {@code done} (réponse + fichiers), et
     * {@code error} (code d'erreur). Le gating et le flag sont résolus ici (thread de requête) où le
     * SecurityContext est disponible ; le relais s'exécute sur le pool SSE.
     */
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID id, @Valid @RequestBody AtelierAgentRequest request) {
        UUID userId = currentUser.requireId();
        // Résolus sur le thread de requête (jamais d'exception synchrone => pas de 406) et relayés
        // comme booléens : une erreur d'accès/flag est émise DANS le flux ({@code error}).
        boolean allowed = atelierAccess.hasAccess();
        boolean enabled = properties.enabled();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        chatStreamExecutor.execute(() -> relay(emitter, userId, id, request.message(), allowed, enabled));
        return emitter;
    }

    /** Exécute le run en relayant chaque étape ; traduit toute erreur en événement SSE {@code error}. */
    private void relay(SseEmitter emitter, UUID userId, UUID workspaceId, String message,
            boolean allowed, boolean enabled) {
        if (!allowed) {
            sendError(emitter, "forbidden");
            return;
        }
        if (!enabled) {
            // Flag off : aucun appel Anthropic, erreur émise dans le flux.
            sendError(emitter, "agent_disabled");
            return;
        }
        try {
            AtelierAgentListener listener = new AtelierAgentListener() {
                @Override
                public void onAgentText(String text) {
                    sendAgent(emitter, text);
                }

                @Override
                public void onAction(String tool, String detail) {
                    sendAction(emitter, tool, detail);
                }

                @Override
                public void onStatus(String state) {
                    sendStatus(emitter, state);
                }
            };
            AtelierSessionResult result = sessionService.runTaskStreaming(userId, workspaceId, message, listener);
            emitter.send(SseEmitter.event().name("done")
                    .data(new StreamDone(result.reply(), result.changedFiles())));
            emitter.complete();
        } catch (WorkspaceNotFoundException ex) {
            sendError(emitter, "workspace_not_found");
        } catch (AtelierAgentDisabledException ex) {
            sendError(emitter, "agent_disabled");
        } catch (AgentSessionTimeoutException ex) {
            sendError(emitter, "session_timeout");
        } catch (AgentProviderException ex) {
            sendError(emitter, "provider_error");
        } catch (StreamAbortedException | IOException ex) {
            // Le client s'est déconnecté pendant l'émission : on clôt (la session est déjà terminée).
            emitter.complete();
        } catch (RuntimeException ex) {
            log.warn("Échec inattendu du relais SSE d'exécution de l'atelier");
            sendError(emitter, "internal_error");
        }
    }

    /** Émet un fragment de texte de l'agent ; une déconnexion client interrompt le relais. */
    private void sendAgent(SseEmitter emitter, String text) {
        try {
            emitter.send(SseEmitter.event().name("agent").data(new StreamAgent(text)));
        } catch (IOException | IllegalStateException ex) {
            throw new StreamAbortedException();
        }
    }

    /** Émet une action (usage d'outil) ; une déconnexion client interrompt le relais. */
    private void sendAction(SseEmitter emitter, String tool, String detail) {
        try {
            emitter.send(SseEmitter.event().name("action").data(new StreamAction(tool, detail)));
        } catch (IOException | IllegalStateException ex) {
            throw new StreamAbortedException();
        }
    }

    /** Émet une transition d'état ; une déconnexion client interrompt le relais. */
    private void sendStatus(SseEmitter emitter, String state) {
        try {
            emitter.send(SseEmitter.event().name("status").data(new StreamStatus(state)));
        } catch (IOException | IllegalStateException ex) {
            throw new StreamAbortedException();
        }
    }

    private void sendError(SseEmitter emitter, String code) {
        try {
            emitter.send(SseEmitter.event().name("error").data(new StreamError(code)));
        } catch (IOException | IllegalStateException ignored) {
            // Client déjà parti : rien à faire de plus.
        }
        emitter.complete();
    }

    /** Interruption interne : le client a fermé le flux pendant l'émission. */
    private static final class StreamAbortedException extends RuntimeException {
    }

    /** Charges utiles JSON des événements SSE. */
    record StreamAgent(String text) {
    }

    record StreamAction(String tool, String detail) {
    }

    record StreamStatus(String state) {
    }

    record StreamDone(String reply, List<String> changedFiles) {
    }

    record StreamError(String error) {
    }
}
