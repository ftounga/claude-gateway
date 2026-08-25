package fr.claudegateway.atelier;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import fr.claudegateway.atelier.agent.AgentCreditExhaustedException;
import fr.claudegateway.atelier.agent.AgentProviderException;
import fr.claudegateway.atelier.agent.AgentSessionTimeoutException;
import fr.claudegateway.atelier.agent.AtelierAgentDisabledException;
import fr.claudegateway.atelier.agent.AtelierAgentListener;
import fr.claudegateway.atelier.agent.AtelierSessionResult;
import fr.claudegateway.atelier.agent.AtelierSessionService;
import fr.claudegateway.atelier.dto.AgentConfirmRequest;
import fr.claudegateway.atelier.dto.AgentConfirmationRequest;
import fr.claudegateway.atelier.dto.AgentConfirmationResponse;
import fr.claudegateway.atelier.dto.AtelierAgentRequest;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.quota.QuotaExceededException;
import fr.claudegateway.quota.SandboxLimitExceededException;
import fr.claudegateway.shared.error.ErrorResponse;
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
 * 406 sur cet endpoint SSE).</p>
 *
 * <p>Depuis F-30 SF-30-04 (ADR-014), la session est <b>persistante par workspace</b> : elle n'est plus
 * terminée à la fin de chaque run (une session {@code idle} n'est pas facturée). Sa fin de vie est
 * explicite, via {@code DELETE /workspaces/{id}/agent/session}.</p>
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
                    sendAction(emitter, tool, null, detail);
                }

                @Override
                public void onAction(String tool, String toolUseId, String detail) {
                    sendAction(emitter, tool, toolUseId, detail);
                }

                @Override
                public void onActionResult(String tool, String toolUseId, String output, boolean error) {
                    sendActionResult(emitter, tool, toolUseId, output, error);
                }

                @Override
                public void onStatus(String state) {
                    sendStatus(emitter, state);
                }

                @Override
                public void onConfirmationRequest(String tool, String confirmationId, String detail) {
                    sendConfirmRequest(emitter, tool, confirmationId, detail);
                }

                @Override
                public void onConfirmationResolved(String confirmationId, String decision) {
                    sendConfirmResolved(emitter, confirmationId, decision);
                }
            };
            AtelierSessionResult result = sessionService.runTaskStreaming(userId, workspaceId, message, listener);
            emitter.send(SseEmitter.event().name("done")
                    .data(new StreamDone(result.reply(), result.changedFiles(),
                            result.inputTokens(), result.outputTokens(), result.activeSeconds(),
                            result.interrupted(), result.budgetReached())));
            emitter.complete();
        } catch (WorkspaceNotFoundException ex) {
            sendError(emitter, "workspace_not_found");
        } catch (QuotaExceededException ex) {
            // Pré-vol quota tokens épuisé : aucune session créée (aucun coût), erreur dans le flux.
            sendError(emitter, "quota_exceeded");
        } catch (SandboxLimitExceededException ex) {
            // Pré-vol plafond de bac à sable atteint : aucune session créée, erreur dans le flux.
            sendError(emitter, "sandbox_limit");
        } catch (AtelierAgentDisabledException ex) {
            sendError(emitter, "agent_disabled");
        } catch (AgentSessionTimeoutException ex) {
            sendError(emitter, "session_timeout");
        } catch (AgentCreditExhaustedException ex) {
            // Crédit de la PLATEFORME épuisé (F-30 SF-30-08) : réessayer ne peut pas aboutir — code
            // distinct de `provider_error`, pour ne pas inviter l'utilisateur à recommencer en vain.
            sendError(emitter, "credit_exhausted");
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

    /**
     * Termine la session sandbox du workspace et efface son identifiant (F-30 SF-30-04) : le message
     * suivant repartira d'une sandbox neuve. Contrepartie de la session persistante — une sandbox
     * longue-vie détenant l'état d'un projet doit avoir une fin de vie explicite (ADR-014).
     *
     * <p>Endpoint JSON classique (pas SSE) : l'isolation {@code user_id} est appliquée par
     * {@code requireOwned} <b>avant tout appel au fournisseur</b>, et un workspace non possédé produit
     * le 404 habituel.</p>
     */
    @DeleteMapping("/session")
    public ResponseEntity<Void> resetSession(@PathVariable UUID id) {
        sessionService.resetSession(currentUser.requireId(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Répond à une demande d'autorisation posée par l'agent (F-33 / SF-33-02) : autorise la commande,
     * ou la refuse avec un motif que l'agent recevra.
     *
     * <p>Le run, lui, attend sur son flux SSE : cette réponse arrive sur une <b>autre requête</b>, est
     * postée à la session chez le fournisseur, et la boucle d'attente la voit revenir dans le flux
     * d'events. Sans réponse dans le délai imparti, la commande est <b>refusée</b> — le silence ne
     * vaut pas autorisation. Endpoint JSON classique : l'isolation {@code user_id} est appliquée par
     * {@code requireOwned} avant tout appel au fournisseur.</p>
     */
    @PostMapping("/confirm")
    public ResponseEntity<Void> confirm(@PathVariable UUID id,
            @Valid @RequestBody AgentConfirmRequest request) {
        sessionService.confirmToolUse(currentUser.requireId(), id, request.toolUseId(),
                request.allows(), request.reason());
        return ResponseEntity.noContent().build();
    }

    /**
     * Active ou désactive la <b>demande d'autorisation avant exécution</b> pour ce projet
     * (F-33 / SF-33-01). Une fois posée, la session est ouverte avec {@code always_ask} sur le seul
     * outil qui exécute : l'agent demande avant chaque commande, au lieu de tout exécuter.
     *
     * <p>La politique d'outils étant fixée à l'ouverture de la session, la réponse porte
     * {@code appliesToCurrentSession} : à {@code false}, la sandbox déjà ouverte garde sa politique,
     * et c'est la réinitialisation (F-30 SF-30-06) qui appliquera la nouvelle. Endpoint JSON
     * classique : l'isolation {@code user_id} est appliquée par {@code requireOwned} avant toute
     * écriture.</p>
     */
    @PutMapping("/confirmation")
    public AgentConfirmationResponse setConfirmation(@PathVariable UUID id,
            @Valid @RequestBody AgentConfirmationRequest request) {
        AtelierSessionService.AgentConfirmationState state = sessionService.setAskBeforeBash(
                currentUser.requireId(), id, Boolean.TRUE.equals(request.enabled()));
        return new AgentConfirmationResponse(state.enabled(), state.appliesToCurrentSession());
    }

    /**
     * Interrompt le run en cours sur la session du workspace (F-32 / SF-32-01) : relaie
     * {@code user.interrupt} au fournisseur, qui ramène la session à une <b>frontière sûre</b> puis la
     * repasse {@code idle}. Sans cela, une commande partie de travers tourne jusqu'au timeout de dix
     * minutes, avec du temps de bac à sable facturé, sans que l'utilisateur puisse agir.
     *
     * <p>L'arrêt est <b>asynchrone</b> : cette réponse dit que la demande est partie, pas que le run
     * est fini. Le flux SSE en cours se clôt de lui-même sur le {@code done} qui suit, en portant
     * {@code interrupted}. Endpoint JSON classique : l'isolation {@code user_id} est appliquée par
     * {@code requireOwned} <b>avant tout appel au fournisseur</b>.</p>
     */
    @PostMapping("/interrupt")
    public ResponseEntity<Void> interrupt(@PathVariable UUID id) {
        sessionService.interruptSession(currentUser.requireId(), id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Échec de relais au fournisseur sur les endpoints JSON de ce contrôleur (F-32 / SF-32-01).
     * Cantonné ici : le flux SSE, lui, traduit ses erreurs en événement {@code error} dans le flux.
     * Un {@code 502} plutôt qu'un {@code 500} — la panne est chez le fournisseur, pas dans la Gateway.
     */
    @ExceptionHandler(AgentProviderException.class)
    public ResponseEntity<ErrorResponse> handleProviderFailure(AgentProviderException ex) {
        log.debug("Relais au fournisseur d'agents en échec sur un endpoint JSON de l'atelier.");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(new ErrorResponse("provider_error",
                        "Le service d'exécution n'a pas pu traiter la demande. Veuillez réessayer."));
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
    private void sendAction(SseEmitter emitter, String tool, String toolUseId, String detail) {
        try {
            emitter.send(SseEmitter.event().name("action").data(new StreamAction(tool, toolUseId, detail)));
        } catch (IOException | IllegalStateException ex) {
            throw new StreamAbortedException();
        }
    }

    /**
     * Émet la sortie d'une commande (F-30 SF-30-01) ; une déconnexion client interrompt le relais.
     * Événement <b>additif</b> : un client qui l'ignore conserve le comportement antérieur.
     */
    private void sendActionResult(SseEmitter emitter, String tool, String toolUseId, String output, boolean error) {
        try {
            emitter.send(SseEmitter.event().name("action_result")
                    .data(new StreamActionResult(tool, toolUseId, output, error)));
        } catch (IOException | IllegalStateException ex) {
            throw new StreamAbortedException();
        }
    }

    /**
     * Émet une demande d'autorisation (F-33 / SF-33-02) : l'écran affiche la commande et attend une
     * décision. Événement <b>additif</b> — un client qui l'ignore ne voit rien de plus qu'avant, et
     * la commande finira refusée par expiration du délai.
     */
    private void sendConfirmRequest(SseEmitter emitter, String tool, String confirmationId, String detail) {
        try {
            emitter.send(SseEmitter.event().name("confirm_request")
                    .data(new StreamConfirmRequest(confirmationId, tool, detail)));
        } catch (IOException | IllegalStateException ex) {
            throw new StreamAbortedException();
        }
    }

    /** Émet la résolution d'une demande d'autorisation, pour que l'écran retire l'invite. */
    private void sendConfirmResolved(SseEmitter emitter, String confirmationId, String decision) {
        try {
            emitter.send(SseEmitter.event().name("confirm_resolved")
                    .data(new StreamConfirmResolved(confirmationId, decision)));
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

    record StreamAction(String tool, String toolUseId, String detail) {
    }

    record StreamActionResult(String tool, String toolUseId, String output, boolean error) {
    }

    record StreamStatus(String state) {
    }

    /**
     * Demande d'autorisation en attente (F-33 / SF-33-02). {@code toolUseId} est l'identifiant à
     * renvoyer pour trancher ; {@code detail} porte la commande, telle que l'agent veut la lancer.
     */
    record StreamConfirmRequest(String toolUseId, String tool, String detail) {
    }

    /** Demande tranchée : {@code allow}, {@code deny}, ou {@code timeout} (refus automatique). */
    record StreamConfirmResolved(String toolUseId, String decision) {
    }

    /**
     * Fin de run. Les champs de consommation (F-30 SF-30-05) sont <b>additifs</b> : un client qui les
     * ignore se comporte comme avant. À zéro, ils signifient « inconnu » (relevé best-effort manqué).
     * {@code interrupted} (F-32 SF-32-01), également additif, dit que le tour s'est arrêté sur demande
     * de l'utilisateur — il est conservé et décompté comme tout autre tour.
     */
    /**
     * Fin de run relayée au client. {@code budgetReached} dit que le tour s'est arrêté sur le
     * <b>plafond de dépense</b> de la session (F-36 SF-36-01) : le tour est conservé et facturé,
     * mais l'écran doit le distinguer d'un quota mensuel épuisé.
     */
    record StreamDone(String reply, List<String> changedFiles, long inputTokens, long outputTokens,
            long activeSeconds, boolean interrupted, boolean budgetReached) {
    }

    record StreamError(String error) {
    }
}
