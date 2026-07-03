package fr.claudegateway.chat;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import fr.claudegateway.ai.AIProviderException;
import fr.claudegateway.ai.AIProviderUnavailableException;
import fr.claudegateway.ai.ModelCatalog;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.chat.ChatService.ChatResult;
import fr.claudegateway.chat.ChatService.StreamContext;
import fr.claudegateway.chat.dto.ChatRequest;
import fr.claudegateway.chat.dto.ChatResponse;
import fr.claudegateway.chat.dto.MessageResponse;
import fr.claudegateway.chat.dto.ModelsResponse;
import jakarta.validation.Valid;

/**
 * Endpoints du proxy de chat Hosted. L'identité provient exclusivement du {@link CurrentUser}
 * (JWT) : l'isolation {@code user_id} est appliquée dans le service, jamais depuis un paramètre client.
 */
@RestController
@RequestMapping("/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** Durée de vie max d'un flux SSE (garde-fou ; un flux nominal se clôt bien avant). */
    private static final long STREAM_TIMEOUT_MS = 300_000L;

    private final ChatService chatService;
    private final CurrentUser currentUser;
    private final ModelCatalog modelCatalog;
    private final Executor chatStreamExecutor;

    public ChatController(ChatService chatService, CurrentUser currentUser, ModelCatalog modelCatalog,
            @Qualifier("chatStreamExecutor") Executor chatStreamExecutor) {
        this.chatService = chatService;
        this.currentUser = currentUser;
        this.modelCatalog = modelCatalog;
        this.chatStreamExecutor = chatStreamExecutor;
    }

    @PostMapping
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        UUID userId = currentUser.requireId();
        ChatResult result = chatService.reply(userId, request.conversationId(), request.message(),
                request.model(), request.attachmentIds(), request.libraryDocumentIds());
        return new ChatResponse(
                result.conversation().getId(),
                MessageResponse.from(result.assistantMessage()),
                result.conversation().getModel());
    }

    /**
     * Chat <b>en streaming</b> (SF-02-04) : relaie token-par-token la réponse de Claude en SSE.
     * Le pré-vol (quota, modèle, isolation, persistance du message USER) est synchrone : ses erreurs
     * remontent en réponse HTTP normale (402/400/404) avant l'ouverture du flux. Le relais et la
     * persistance finale s'exécutent sur un thread dédié.
     */
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        UUID userId = currentUser.requireId();
        StreamContext context = chatService.prepareStream(userId, request.conversationId(),
                request.message(), request.model(), request.attachmentIds(), request.libraryDocumentIds());

        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        chatStreamExecutor.execute(() -> relay(emitter, context));
        return emitter;
    }

    private void relay(SseEmitter emitter, StreamContext context) {
        try {
            Message assistant = chatService.streamAndPersist(context, delta -> sendToken(emitter, delta));
            emitter.send(SseEmitter.event().name("done").data(new StreamDone(
                    context.conversation().getId(), assistant.getId(), context.conversation().getModel())));
            emitter.complete();
        } catch (AIProviderUnavailableException ex) {
            sendError(emitter, "provider_unavailable");
        } catch (AIProviderException ex) {
            sendError(emitter, "provider_error");
        } catch (StreamAbortedException | IOException ex) {
            // Le client s'est déconnecté pendant l'émission : on clôt sans persister davantage.
            emitter.complete();
        } catch (RuntimeException ex) {
            log.warn("Échec inattendu du relais SSE de chat");
            sendError(emitter, "internal_error");
        }
    }

    /** Émet un fragment de texte ; une déconnexion client interrompt le relais. */
    private void sendToken(SseEmitter emitter, String delta) {
        try {
            emitter.send(SseEmitter.event().name("token").data(new StreamToken(delta)));
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
    record StreamToken(String text) {
    }

    record StreamDone(UUID conversationId, UUID messageId, String model) {
    }

    record StreamError(String error) {
    }

    /** Modèles sélectionnables et modèle par défaut (aucune donnée sensible). */
    @GetMapping("/models")
    public ModelsResponse models() {
        return new ModelsResponse(modelCatalog.defaultModel(), modelCatalog.availableModels());
    }
}
