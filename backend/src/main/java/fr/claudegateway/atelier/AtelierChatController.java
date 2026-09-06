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
import fr.claudegateway.atelier.dto.AtelierResumeResponse;
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
    private final AtelierThreadService atelierThreadService;
    private final CurrentUser currentUser;
    private final AtelierAccessService atelierAccess;
    private final Executor chatStreamExecutor;

    public AtelierChatController(AtelierChatService atelierChatService,
            AtelierThreadService atelierThreadService, CurrentUser currentUser,
            AtelierAccessService atelierAccess,
            @Qualifier("chatStreamExecutor") Executor chatStreamExecutor) {
        this.atelierChatService = atelierChatService;
        this.atelierThreadService = atelierThreadService;
        this.currentUser = currentUser;
        this.atelierAccess = atelierAccess;
        this.chatStreamExecutor = chatStreamExecutor;
    }

    @PostMapping
    public AtelierChatResponse chat(@PathVariable UUID id, @Valid @RequestBody AtelierChatRequest request) {
        atelierAccess.requireAccess();
        AtelierChatResult result = atelierChatService.chat(currentUser.requireId(), id, request.message());
        return new AtelierChatResponse(result.reply(), result.actions(), result.messageId(),
                result.inputTokens(), result.outputTokens(), result.activeSeconds(),
                result.budgetReached());
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
     * Dépose une <b>précision</b> pour le tour en cours (F-39 / SF-39-19) : elle sera lue au début
     * de l'itération suivante, et l'agent en tiendra compte sans que rien s'arrête.
     *
     * <p>À ne pas confondre avec l'interruption, juste en dessous : celle-ci arrête le tour, celle-là
     * l'enrichit. C'est le geste le plus fréquent — préciser sans casser.</p>
     */
    @PostMapping("/steer")
    public ResponseEntity<Void> steer(@PathVariable UUID id,
            @Valid @RequestBody AtelierChatRequest request) {
        atelierAccess.requireAccess();
        atelierChatService.steer(currentUser.requireId(), id, request.message());
        return ResponseEntity.noContent().build();
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
                request.allows(), request.reason(), request.allowsAll());
        return ResponseEntity.noContent().build();
    }

    /**
     * État de reprise du fil (F-39 / SF-39-04, décision D5) : ce que le prochain tour rejouera, et
     * s'il faut poser la question. Par défaut le fil reprend en silence — l'écran n'appelle cette
     * route que pour savoir s'il doit, exceptionnellement, proposer un choix.
     *
     * <p>Isolation {@code user_id} appliquée par le service ({@code requireOwned} : 404 sur un
     * projet d'autrui).</p>
     */
    @GetMapping("/resume")
    public AtelierResumeResponse resume(@PathVariable UUID id) {
        atelierAccess.requireAccess();
        return atelierThreadService.resumeState(currentUser.requireId(), id);
    }

    /**
     * Nouveau départ (F-39 / SF-39-04, décision D1) : les tours passés cessent d'être rejoués.
     *
     * <p><b>Rien n'est supprimé</b> — {@code GET /workspaces/{id}/chat} continue de renvoyer toute
     * la conversation. Seule la mémoire que l'agent en a repart de zéro, ce qui rend le geste
     * réversible : l'utilisateur peut toujours relire, et rien ne l'empêche de reparler du même
     * sujet.</p>
     */
    @PostMapping("/restart")
    public AtelierResumeResponse restart(@PathVariable UUID id) {
        atelierAccess.requireAccess();
        return atelierThreadService.restart(currentUser.requireId(), id);
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
                public void onProgress(long tokens) {
                    sendProgress(emitter, tokens);
                }

                @Override
                public void onPlan(fr.claudegateway.atelier.AtelierPlan plan) {
                    sendPlan(emitter, plan);
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
                    .data(new StreamDone(result.reply(), result.actions(), result.messageId(),
                            result.inputTokens(), result.outputTokens(), result.activeSeconds(),
                            result.budgetReached())));
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
     * Émet la consommation cumulée du tour (F-39 / SF-39-15). Une déconnexion du client ne doit pas
     * tuer le tour : comme la sortie de commande, c'est un <b>confort d'affichage</b>, et le tour
     * tourne déjà. On abandonne le relais, pas le travail.
     */
    /**
     * Émet le plan de travail du tour (F-39 / SF-39-13). Même règle que la consommation : c'est un
     * confort d'affichage, un client parti n'arrête pas le travail.
     */
    private void sendPlan(SseEmitter emitter, fr.claudegateway.atelier.AtelierPlan plan) {
        try {
            emitter.send(SseEmitter.event().name("plan").data(new StreamPlan(
                    plan.steps().stream()
                            .map(step -> new StreamPlanStep(step.title(), step.status().label()))
                            .toList())));
        } catch (IOException | IllegalStateException ex) {
            // Client parti : le plan reste persisté avec le tour, et se relit au rechargement.
        }
    }

    private void sendProgress(SseEmitter emitter, long tokens) {
        try {
            emitter.send(SseEmitter.event().name("progress").data(new StreamProgress(tokens)));
        } catch (IOException | IllegalStateException ex) {
            // Client parti : la consommation reste relevée et persistée avec le tour.
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

    /**
     * Fin de tour relayée au client. Les quatre derniers champs sont <b>additifs</b>
     * (F-39 / SF-39-15) : un écran qui les ignore se comporte exactement comme avant.
     * {@code budgetReached} dit que le tour s'est arrêté sur le <b>plafond de consommation</b> du
     * message — jamais sur le budget de temps, qui dit déjà sa cause dans {@code reply}.
     */
    record StreamDone(String reply, List<AtelierAction> actions, UUID messageId, long inputTokens,
            long outputTokens, long activeSeconds, boolean budgetReached) {
    }

    /** Consommation cumulée du tour, relayée au fil de l'eau (F-39 / SF-39-15). */
    /** Plan de travail relayé au fil de l'eau (F-39 / SF-39-13) : la liste COMPLÈTE à chaque fois. */
    record StreamPlan(List<StreamPlanStep> steps) {
    }

    /** Une étape du plan, telle que l'écran l'affiche. */
    record StreamPlanStep(String title, String status) {
    }

    record StreamProgress(long tokens) {
    }

    record StreamError(String error) {
    }
}
