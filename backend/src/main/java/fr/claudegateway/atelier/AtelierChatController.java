package fr.claudegateway.atelier;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import fr.claudegateway.ai.AIProviderException;
import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.atelier.AtelierChatService.AtelierChatResult;
import fr.claudegateway.atelier.AtelierProgressListener.AtelierStepEvent;
import fr.claudegateway.atelier.dto.AtelierChatRequest;
import fr.claudegateway.atelier.dto.AtelierChatResponse;
import fr.claudegateway.atelier.dto.AtelierChatResponse.AtelierAction;
import fr.claudegateway.atelier.dto.AtelierMessageResponse;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.quota.QuotaExceededException;
import jakarta.validation.Valid;

/**
 * Endpoints de conversation de l'Atelier (F-28 / SF-28-02 + SF-28-05) : Claude lit/édite les fichiers
 * du workspace via une boucle tool-use. Identité issue du {@link CurrentUser} ; isolation
 * {@code user_id} appliquée dans le service (workspace d'un autre utilisateur => 404).
 */
@RestController
@RequestMapping("/workspaces/{id}/chat")
public class AtelierChatController {

    private static final Logger log = LoggerFactory.getLogger(AtelierChatController.class);

    /** Durée de vie max d'un flux SSE (garde-fou ; un flux nominal se clôt bien avant). */
    private static final long STREAM_TIMEOUT_MS = 300_000L;

    private final AtelierChatService atelierChatService;
    private final CurrentUser currentUser;
    private final Executor chatStreamExecutor;

    public AtelierChatController(AtelierChatService atelierChatService, CurrentUser currentUser,
            @Qualifier("chatStreamExecutor") Executor chatStreamExecutor) {
        this.atelierChatService = atelierChatService;
        this.currentUser = currentUser;
        this.chatStreamExecutor = chatStreamExecutor;
    }

    @PostMapping
    public AtelierChatResponse chat(@PathVariable UUID id, @Valid @RequestBody AtelierChatRequest request) {
        AtelierChatResult result = atelierChatService.chat(currentUser.requireId(), id, request.message());
        return new AtelierChatResponse(result.reply(), result.actions(), result.messageId());
    }

    /**
     * Chat d'atelier <b>en streaming</b> (SF-28-05) : relaie chaque étape (action fichier, commentaire
     * de tour) puis la réponse finale en SSE, sur un thread dédié. Les erreurs de pré-vol (quota,
     * isolation) sont émises <b>dans le flux</b> (événement {@code error} + {@code complete}), jamais
     * via l'{@code @ExceptionHandler} JSON global (qui produirait un 406 sur un endpoint SSE).
     */
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@PathVariable UUID id, @Valid @RequestBody AtelierChatRequest request) {
        UUID userId = currentUser.requireId();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        chatStreamExecutor.execute(() -> relay(emitter, userId, id, request.message()));
        return emitter;
    }

    @GetMapping
    public List<AtelierMessageResponse> history(@PathVariable UUID id) {
        return atelierChatService.history(currentUser.requireId(), id).stream()
                .map(AtelierMessageResponse::from)
                .toList();
    }

    /** Exécute la boucle tool-use en relayant chaque étape ; traduit toute erreur en événement SSE. */
    private void relay(SseEmitter emitter, UUID userId, UUID workspaceId, String message) {
        try {
            AtelierProgressListener listener = new AtelierProgressListener() {
                @Override
                public void onAction(AtelierStepEvent step) {
                    sendAction(emitter, step);
                }

                @Override
                public void onText(String text) {
                    sendText(emitter, text);
                }
            };
            AtelierChatResult result = atelierChatService.chatStreaming(userId, workspaceId, message, listener);
            emitter.send(SseEmitter.event().name("done")
                    .data(new StreamDone(result.reply(), result.actions(), result.messageId())));
            emitter.complete();
        } catch (QuotaExceededException ex) {
            sendError(emitter, "quota_exceeded");
        } catch (WorkspaceNotFoundException ex) {
            sendError(emitter, "workspace_not_found");
        } catch (AIProviderUnavailableException ex) {
            sendError(emitter, "provider_unavailable");
        } catch (AIProviderException ex) {
            sendError(emitter, "provider_error");
        } catch (StreamAbortedException | IOException ex) {
            // Le client s'est déconnecté pendant l'émission : on clôt sans persister davantage.
            emitter.complete();
        } catch (RuntimeException ex) {
            log.warn("Échec inattendu du relais SSE de l'atelier");
            sendError(emitter, "internal_error");
        }
    }

    /** Émet une étape d'action ; une déconnexion client interrompt le relais. */
    private void sendAction(SseEmitter emitter, AtelierStepEvent step) {
        try {
            emitter.send(SseEmitter.event().name("action").data(step));
        } catch (IOException | IllegalStateException ex) {
            throw new StreamAbortedException();
        }
    }

    /** Émet un commentaire de tour ; une déconnexion client interrompt le relais. */
    private void sendText(SseEmitter emitter, String text) {
        try {
            emitter.send(SseEmitter.event().name("text").data(new StreamText(text)));
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
    record StreamText(String text) {
    }

    record StreamDone(String reply, List<AtelierAction> actions, UUID messageId) {
    }

    record StreamError(String error) {
    }
}
