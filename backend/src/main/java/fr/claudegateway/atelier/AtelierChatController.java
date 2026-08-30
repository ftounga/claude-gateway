package fr.claudegateway.atelier;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
import fr.claudegateway.atelier.AtelierProgressListener.AtelierConfirmRequest;
import fr.claudegateway.atelier.AtelierProgressListener.AtelierConfirmResolved;
import fr.claudegateway.atelier.dto.AgentConfirmRequest;
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

    /**
     * Durée de vie max d'un flux SSE (garde-fou ; un flux nominal se clôt bien avant).
     *
     * <p>Relevée de 300 000 à 900 000 ms en F-38 / SF-38-07 : avec l'outil {@code bash}, un tour peut
     * légitimement enchaîner plusieurs commandes de deux minutes. À 5 minutes, l'émetteur se fermait
     * <b>pendant</b> que la boucle continuait d'exécuter des commandes sur la machine de
     * l'utilisateur — écran figé, travail invisible. La borne qui fait foi est désormais le
     * <b>budget de tour</b> ({@link AtelierChatService#TURN_BUDGET_MS}, 600 000 ms) : la boucle rend
     * la main avant que ce garde-fou ne se déclenche.</p>
     */
    private static final long STREAM_TIMEOUT_MS = 900_000L;

    private final AtelierChatService atelierChatService;
    private final CurrentUser currentUser;
    private final AtelierAccessService atelierAccess;
    private final Executor chatStreamExecutor;

    public AtelierChatController(AtelierChatService atelierChatService, CurrentUser currentUser,
            AtelierAccessService atelierAccess,
            @Qualifier("chatStreamExecutor") Executor chatStreamExecutor) {
        this.atelierChatService = atelierChatService;
        this.currentUser = currentUser;
        this.atelierAccess = atelierAccess;
        this.chatStreamExecutor = chatStreamExecutor;
    }

    @PostMapping
    public AtelierChatResponse chat(@PathVariable UUID id, @Valid @RequestBody AtelierChatRequest request) {
        atelierAccess.requireAccess();
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
        // Le gating est résolu ICI (thread de requête) où le SecurityContext est disponible : le relais
        // s'exécute sur un thread du pool SSE qui n'hérite pas du contexte de sécurité. On capture un
        // booléen (jamais d'exception synchrone => pas de 406 sur cet endpoint SSE) et l'erreur d'accès
        // est émise DANS le flux ({@code error: forbidden}), comme les autres erreurs de pré-vol.
        boolean hasAccess = atelierAccess.hasAccess();
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        chatStreamExecutor.execute(() -> relay(emitter, userId, id, request.message(), hasAccess));
        return emitter;
    }

    /**
     * Interrompt le tour d'atelier en cours sur ce projet (F-38 / SF-38-07, même geste que F-32
     * SF-32-02). La commande éventuellement lancée sur la machine de l'utilisateur est <b>tuée</b>,
     * et la boucle s'arrête à la frontière sûre suivante.
     *
     * <p>Volontairement <b>idempotent</b> : interrompre alors que rien ne tourne n'est pas une
     * erreur (la marque est effacée à l'ouverture du prochain tour). L'isolation {@code user_id} est
     * appliquée par le service ({@code requireOwned} d'abord : 404 sur un projet d'autrui).</p>
     */
    @PostMapping("/interrupt")
    public ResponseEntity<Void> interrupt(@PathVariable UUID id) {
        atelierAccess.requireAccess();
        atelierChatService.interruptChat(currentUser.requireId(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Tranche une demande d'autorisation posée par le tour en cours (F-38 / SF-38-08, décision D7) :
     * autorise la commande, ou la refuse avec un motif que le modèle recevra.
     *
     * <p>Endpoint JSON classique (pas SSE) : le tour attend sur son flux, cette réponse arrive sur
     * une autre requête. Sans réponse dans le délai imparti, la commande est <b>refusée</b> — le
     * silence ne vaut pas autorisation. L'isolation {@code user_id} est appliquée par le service
     * ({@code requireOwned} d'abord : 404 sur un projet d'autrui), et une demande qui n'attend plus
     * rien renvoie 409 plutôt que de laisser croire à une autorisation passée.</p>
     */
    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@PathVariable UUID id,
            @Valid @RequestBody AgentConfirmRequest request) {
        atelierAccess.requireAccess();
        atelierChatService.confirmToolUse(currentUser.requireId(), id, request.toolUseId(),
                request.allows(), request.reason());
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public List<AtelierMessageResponse> history(@PathVariable UUID id) {
        atelierAccess.requireAccess();
        return atelierChatService.history(currentUser.requireId(), id).stream()
                .map(AtelierMessageResponse::from)
                .toList();
    }

    /** Exécute la boucle tool-use en relayant chaque étape ; traduit toute erreur en événement SSE. */
    private void relay(SseEmitter emitter, UUID userId, UUID workspaceId, String message, boolean hasAccess) {
        try {
            if (!hasAccess) {
                throw new AtelierAccessDeniedException();
            }
            AtelierProgressListener listener = new AtelierProgressListener() {
                @Override
                public void onAction(AtelierStepEvent step) {
                    sendAction(emitter, step);
                }

                @Override
                public void onText(String text) {
                    sendText(emitter, text);
                }

                @Override
                public void onOutput(String chunk) {
                    sendOutput(emitter, chunk);
                }

                @Override
                public void onConfirmRequest(AtelierConfirmRequest request) {
                    sendConfirmRequest(emitter, request);
                }

                @Override
                public void onConfirmResolved(AtelierConfirmResolved resolved) {
                    sendConfirmResolved(emitter, resolved);
                }
            };
            AtelierChatResult result = atelierChatService.chatStreaming(userId, workspaceId, message, listener);
            emitter.send(SseEmitter.event().name("done")
                    .data(new StreamDone(result.reply(), result.actions(), result.messageId())));
            emitter.complete();
        } catch (AtelierAccessDeniedException ex) {
            sendError(emitter, "forbidden");
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

    /**
     * Émet un fragment de sortie de commande (F-38 / SF-38-07). Une déconnexion client ne doit pas
     * tuer le tour : contrairement aux étapes, la sortie est un <b>confort d'affichage</b>, et la
     * commande tourne déjà sur la machine de l'utilisateur. On abandonne le relais, pas le travail.
     */
    private void sendOutput(SseEmitter emitter, String chunk) {
        try {
            emitter.send(SseEmitter.event().name("output").data(new StreamOutput(chunk)));
        } catch (IOException | IllegalStateException ex) {
            // Client parti : la sortie reste agrégée pour le modèle et pour le fil persisté.
        }
    }

    /**
     * Émet une demande d'autorisation (F-38 / SF-38-08). Une déconnexion du client interrompt le
     * relais : sans écran pour trancher, la commande ne doit pas être lancée « en attendant ».
     */
    private void sendConfirmRequest(SseEmitter emitter, AtelierConfirmRequest request) {
        try {
            emitter.send(SseEmitter.event().name("confirm_request").data(request));
        } catch (IOException | IllegalStateException ex) {
            throw new StreamAbortedException();
        }
    }

    /** Émet la résolution d'une demande d'autorisation, pour que l'écran retire l'invite. */
    private void sendConfirmResolved(SseEmitter emitter, AtelierConfirmResolved resolved) {
        try {
            emitter.send(SseEmitter.event().name("confirm_resolved").data(resolved));
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

    /** Fragment de sortie de commande relayé au fil de l'eau (F-38 / SF-38-07). */
    record StreamOutput(String output) {
    }

    record StreamDone(String reply, List<AtelierAction> actions, UUID messageId) {
    }

    record StreamError(String error) {
    }
}
