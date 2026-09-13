package fr.claudegateway.atelier;

import java.util.List;
import java.util.Optional;
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
import org.springframework.web.bind.annotation.RequestParam;
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
import fr.claudegateway.atelier.dto.AtelierTurnStateResponse;
import fr.claudegateway.atelier.live.LiveTurn;
import fr.claudegateway.atelier.live.LiveTurnRegistry;
import fr.claudegateway.atelier.live.PendingApproval;
import fr.claudegateway.atelier.live.RemoteTurnSource;
import fr.claudegateway.atelier.live.SseTurnSubscriber;
import fr.claudegateway.atelier.live.TurnAsides;
import fr.claudegateway.atelier.live.TurnSubscriber;
import fr.claudegateway.atelier.live.WindowedTurnSubscriber;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.byok.ByokKeyRequiredException;
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
    private final Executor turnAttachExecutor;
    private final LiveTurnRegistry liveTurns;
    private final RemoteTurnSource remoteTurns;

    public AtelierChatController(AtelierChatService atelierChatService,
            AtelierThreadService atelierThreadService, CurrentUser currentUser,
            AtelierAccessService atelierAccess,
            @Qualifier("chatStreamExecutor") Executor chatStreamExecutor,
            @Qualifier("turnAttachExecutor") Executor turnAttachExecutor,
            LiveTurnRegistry liveTurns, RemoteTurnSource remoteTurns) {
        this.atelierChatService = atelierChatService;
        this.atelierThreadService = atelierThreadService;
        this.currentUser = currentUser;
        this.atelierAccess = atelierAccess;
        this.chatStreamExecutor = chatStreamExecutor;
        this.turnAttachExecutor = turnAttachExecutor;
        this.liveTurns = liveTurns;
        this.remoteTurns = remoteTurns;
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
        SseEmitter emitter = newEmitter();
        fr.claudegateway.chat.SseStreamDispatch.submit(chatStreamExecutor, emitter,
                () -> relay(emitter, userId, id, request.message(), hasAccess));
        return emitter;
    }


    /**
     * <b>Se rebrancher</b> sur le tour en cours de ce projet (F-84 / SF-84-02).
     *
     * <p>L'écran rouvre le terminal et rejoue ce qu'il a manqué depuis son <b>curseur</b>, puis
     * reprend le direct — ni doublon, ni trou. Chaque événement porte son numéro dans le champ
     * {@code id:} du protocole SSE : c'est ce numéro qu'un écran renvoie ici s'il se rebranche à
     * nouveau. {@code cursor=0} (le défaut) veut dire « je n'ai rien vu », et convient à un écran
     * neuf.</p>
     *
     * <p>Aucun tour vivant — ni ici, ni chez un pair joignable — et le flux dit {@code idle} puis se
     * clôt : c'est la <b>dégradation vers l'état d'origine</b>, celle de {@code RunnerCallRouter}.
     * Rien n'est deviné.</p>
     *
     * <p><b>Une vue rouverte n'est pas un flux de plus</b> : ce rebranchement passe par un exécuteur
     * distinct de celui des flux émetteurs, et ne prend aucune place au registre des terminaux
     * vivants (F-70) — la place appartient à l'onglet, qui n'a pas changé.</p>
     *
     * <p><b>Isolation</b> : le tour est cherché par le couple {@code (userId, workspaceId)}. Le tour
     * d'un autre utilisateur est <b>introuvable</b>, pas « refusé » : on ne se rebranche jamais sur
     * le tour d'autrui.</p>
     *
     * <p><b>Par fenêtres</b> (F-84 / SF-84-04) : avec {@code waitMs}, la réponse se <b>clôt</b> peu
     * après le premier événement de tour livré, ou à l'échéance. C'est ce qui fait passer le direct
     * au travers d'un proxy d'entreprise qui retient un flux SSE jusqu'à sa fin : une réponse close
     * est relâchée, et l'écran se rebranche avec son curseur. Sans {@code waitMs}, rien ne change.</p>
     */
    @GetMapping(path = "/attach", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter attach(@PathVariable UUID id,
            @RequestParam(name = "cursor", required = false) Long cursor,
            @RequestParam(name = "waitMs", required = false) Long waitMs) {
        UUID userId = currentUser.requireId();
        // Gating résolu ICI, comme pour le flux d'émission : le pool n'hérite pas du SecurityContext,
        // et un refus doit partir DANS le flux (jamais un 406 sur un endpoint SSE).
        boolean hasAccess = atelierAccess.hasAccess();
        long from = cursor == null || cursor < 0 ? LiveTurn.FROM_START : cursor;
        SseEmitter emitter = newEmitter();
        fr.claudegateway.chat.SseStreamDispatch.submit(turnAttachExecutor, emitter,
                () -> attachRelay(emitter, userId, id, from, waitMs, hasAccess));
        return emitter;
    }

    /** Le rebranchement historique, sans fenêtre (SF-84-02). */
    SseEmitter attach(UUID id, Long cursor) {
        return attach(id, cursor, null);
    }

    /**
     * L'état du tour de ce projet (F-84 / SF-84-02) : vivant ou non, et à quel curseur il en est.
     *
     * <p>Endpoint JSON classique. Un tour qui tourne sur un <b>autre pod</b> est rendu comme vivant :
     * la question posée est « est-ce que ça tourne ? », et l'endroit où cela tourne n'est pas une
     * affaire d'écran.</p>
     */
    @GetMapping("/turn")
    public AtelierTurnStateResponse turnState(@PathVariable UUID id) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        Optional<LiveTurn> local = liveTurns.find(userId, id);
        if (local.isPresent()) {
            LiveTurn turn = local.get();
            return new AtelierTurnStateResponse(true, turn.turnId(), turn.cursor(),
                    turn.startedAtMs(),
                    turn.pendingApproval().map(AtelierTurnStateResponse.PendingApprovalView::of)
                            .orElse(null));
        }
        return remoteTurns.findRemoteTurn(userId, id)
                .map(state -> new AtelierTurnStateResponse(true, state.turnId(), state.cursor(),
                        state.startedAtMs(),
                        AtelierTurnStateResponse.PendingApprovalView.of(state.pending())))
                .orElseGet(AtelierTurnStateResponse::idle);
    }

    /**
     * Branche ce spectateur sur le tour : d'abord ici, sinon chez un pair, sinon {@code idle}.
     *
     * <p>Dans le cas <b>local</b>, cette méthode rend la main alors que le flux reste ouvert : le
     * spectateur est inscrit au tour, et c'est la fin du tour qui clôra son flux. Dans le cas
     * <b>relayé</b>, elle tient le thread pendant la lecture du pair — d'où l'exécuteur dédié.</p>
     */
    private void attachRelay(SseEmitter emitter, UUID userId, UUID workspaceId, long cursor,
            Long waitMs, boolean hasAccess) {
        SseTurnSubscriber sse = new SseTurnSubscriber(emitter);
        TurnSubscriber subscriber = sse;
        if (!hasAccess) {
            subscriber.deliver(TurnAsides.error("forbidden"));
            subscriber.finish();
            return;
        }
        Optional<LiveTurn> local = liveTurns.find(userId, workspaceId);
        if (local.isPresent()) {
            LiveTurn turn = local.get();
            if (waitMs != null) {
                subscriber = WindowedTurnSubscriber.open(sse, turn, windowTimer(), waitMs);
            }
            if (!subscriber.deliver(TurnAsides.attached(turn.turnId(), turn.cursor(),
                    turn.startedAtMs()))) {
                return;
            }
            if (!turn.attach(subscriber, cursor)) {
                // Le tour s'est terminé entre la recherche et le branchement, ou le spectateur est
                // parti pendant le rejeu : dans les deux cas, il n'y a plus rien à suivre.
                subscriber.finish();
            }
            return;
        }
        Optional<RemoteTurnSource.RemoteTurnState> remote =
                remoteTurns.findRemoteTurn(userId, workspaceId);
        if (remote.isEmpty()) {
            subscriber.deliver(TurnAsides.idle());
            subscriber.finish();
            return;
        }
        if (waitMs != null) {
            // Même fenêtre pour un tour relayé depuis un pair : la réponse se clôt à l'échéance ou
            // après le premier événement, et le relais s'arrête au prochain envoi refusé.
            subscriber = WindowedTurnSubscriber.open(sse, null, windowTimer(), waitMs);
        }
        if (!subscriber.deliver(TurnAsides.attached(remote.get().turnId(), remote.get().cursor(),
                remote.get().startedAtMs()))) {
            return;
        }
        // Le pair peut avoir terminé son tour entre la sonde et le flux : le relais rend alors
        // `false`, et le flux se clôt sans rien inventer.
        remoteTurns.streamRemoteTurn(userId, workspaceId, cursor, subscriber);
        subscriber.finish();
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

    /**
     * L'émetteur du flux. Isolé en une méthode pour qu'un test puisse fournir celui d'un
     * <b>navigateur parti</b> — l'objet même que F-84 devait cesser de confondre avec un ordre
     * d'arrêt. Jamais redéfini en production.
     */
    SseEmitter newEmitter() {
        return new SseEmitter(STREAM_TIMEOUT_MS);
    }

    /** Le minuteur des fenêtres (SF-84-04). Isolé pour qu'un test l'avance à la main. */
    WindowedTurnSubscriber.Timer windowTimer() {
        return WindowedTurnSubscriber.sharedTimer();
    }

    /**
     * Exécute la boucle tool-use en <b>publiant</b> chaque étape dans le tour vivant, et traduit
     * toute erreur en événement {@code error}.
     *
     * <p><b>F-84 / SF-84-01</b> : l'émetteur n'est plus le destinataire des étapes, il est
     * <b>abonné</b> au tour. Un envoi qui échoue détache ce seul spectateur ; le tour, lui, continue.
     * Il ne s'arrête plus que parce qu'il a fini, parce qu'il a atteint son plafond, ou parce que
     * l'utilisateur l'a <b>interrompu</b> explicitement (F-32 / SF-38-07) — jamais parce qu'un
     * navigateur est parti.</p>
     */
    private void relay(SseEmitter emitter, UUID userId, UUID workspaceId, String message, boolean hasAccess) {
        LiveTurn turn = liveTurns.open(userId, workspaceId);
        turn.attach(new SseTurnSubscriber(emitter), LiveTurn.FROM_START);
        String outcome = "echec_fatal";
        try {
            if (!hasAccess) {
                throw new AtelierAccessDeniedException();
            }
            // La demande est prise en main (F-84 / SF-84-04) : premier événement du tour, AVANT tout
            // appel fournisseur — le premier aller-retour peut durer des dizaines de secondes sur un
            // long contexte. Il sert aussi de SONDE à l'écran : sur un réseau direct il arrive en
            // quelques millisecondes ; s'il n'arrive pas, c'est qu'un proxy retient le flux, et
            // l'écran passe au suivi par fenêtres.
            turn.publish("started", new StreamStarted(turn.turnId().toString(), turn.startedAtMs()));
            AtelierProgressListener listener = new AtelierProgressListener() {
                @Override
                public void onAction(AtelierStepEvent step) {
                    turn.publish("action", step);
                }

                @Override
                public void onText(String text) {
                    turn.publish("text", new StreamText(text));
                }

                @Override
                public void onOutput(String chunk) {
                    turn.publish("output", new StreamOutput(chunk));
                }

                @Override
                public void onProgress(long tokens) {
                    turn.publish("progress", new StreamProgress(tokens));
                }

                @Override
                public void onPlan(fr.claudegateway.atelier.AtelierPlan plan) {
                    turn.publish("plan", streamPlan(plan));
                }

                /**
                 * Un BLOC RICHE posé dans le fil (F-89 / SF-89-02) : carte, moments, liste. Relayé
                 * au fil de l'eau, comme le plan — un compte rendu qui n'apparaîtrait qu'à la fin
                 * du tour laisserait l'écran muet pendant qu'un agent lit trente fils.
                 *
                 * <p>Le bloc est déjà VALIDÉ : l'écran n'a rien à filtrer, et c'est exactement ce
                 * qu'on veut — un écran qui écarterait les lignes sans source afficherait un compte
                 * rendu amputé sans le dire.</p>
                 */
                @Override
                public void onCard(String toolUseId,
                        fr.claudegateway.teams.block.TeamsBlockCard card) {
                    turn.publish("card", new StreamCard(toolUseId, card));
                }

                /**
                 * Une demande d'autorisation n'est plus seulement relayée : elle devient l'ÉTAT du
                 * tour (F-84 / SF-84-03). Un écran qui arrive après coup la trouve encore en
                 * attente, au lieu de l'avoir manquée avec le flux qui la portait.
                 */
                @Override
                public void onConfirmRequest(AtelierConfirmRequest request) {
                    turn.publishApprovalRequest(request, new PendingApproval(request.toolUseId(),
                            request.tool(), request.detail(), request.timeoutMs(),
                            System.currentTimeMillis()));
                }

                @Override
                public void onConfirmResolved(AtelierConfirmResolved resolved) {
                    turn.publishApprovalResolved(resolved, resolved.toolUseId());
                }

                /**
                 * Le poste vient de refuser un appel (F-97 / SF-97-02). L'instant est celui du
                 * SERVEUR : l'écran le compare au dernier battement connu, lui aussi en heure
                 * serveur, pour qu'un refus rejoué par le tampon du tour ne rende pas hors ligne un
                 * poste revenu depuis.
                 */
                @Override
                public void onRunnerOffline(UUID hostId) {
                    turn.publish("runner_offline",
                            new StreamRunnerOffline(hostId.toString(), System.currentTimeMillis()));
                }
            };
            AtelierChatResult result = atelierChatService.chatStreaming(userId, workspaceId, message, listener);
            turn.publish("done", new StreamDone(result.reply(), result.actions(), result.messageId(),
                    result.inputTokens(), result.outputTokens(), result.activeSeconds(),
                    result.budgetReached()));
            outcome = "done";
        } catch (AtelierAccessDeniedException ex) {
            outcome = failTurn(turn, "forbidden");
        } catch (QuotaExceededException ex) {
            outcome = failTurn(turn, "quota_exceeded");
        } catch (ByokKeyRequiredException ex) {
            // Offre BYOK sans clé (F-41 / SF-41-02) : refus nommé dans le flux, jamais `internal_error`.
            outcome = failTurn(turn, "byok_key_required");
        } catch (WorkspaceNotFoundException ex) {
            outcome = failTurn(turn, "workspace_not_found");
        } catch (AIProviderUnavailableException ex) {
            outcome = failTurn(turn, "provider_unavailable");
        } catch (AIProviderException ex) {
            outcome = failTurn(turn, "provider_error");
        } catch (RuntimeException ex) {
            log.warn("Échec inattendu de la boucle d'atelier ({})", ex.getClass().getSimpleName());
            outcome = failTurn(turn, "internal_error");
        } finally {
            // Le tour est fini : les spectateurs encore branchés voient leur flux se clore, et le
            // tour quitte le registre. C'est le SEUL endroit qui clôt un flux de tour.
            liveTurns.close(turn);
            // TOUTE fin de flux de tour laisse une ligne (F-84 / SF-84-04) — y compris une erreur
            // de pré-vol ou une panne, où la boucle n'a pas écrit son « Tour d'atelier terminé ».
            // Jamais le message ni la réponse : l'identifiant du tour et l'issue suffisent.
            log.info("Flux de tour clos (workspace={}, tour={}, événements={}, issue={})",
                    workspaceId, turn.turnId(), turn.cursor(), outcome);
        }
    }

    /** Publie l'erreur nommée du tour et rend l'issue à journaliser. */
    private static String failTurn(LiveTurn turn, String code) {
        turn.publish("error", new StreamError(code));
        return code;
    }

    /** Le plan tel qu'il part sur le fil : la liste complète, qui remplace la précédente. */
    private static StreamPlan streamPlan(fr.claudegateway.atelier.AtelierPlan plan) {
        return new StreamPlan(plan.steps().stream()
                .map(step -> new StreamPlanStep(step.title(), step.status().label()))
                .toList());
    }


    /**
     * Un bloc riche relayé au fil de l'eau (F-89 / SF-89-02). Il porte le même {@code toolUseId} que
     * le bloc de transcription qui le rejouera au rechargement : l'écran remplace, il n'empile pas.
     */
    record StreamCard(String toolUseId, fr.claudegateway.teams.block.TeamsBlockCard card) {
    }

    /** Le tour a pris la demande en main (F-84 / SF-84-04) ; {@code startedAt} en ms, heure serveur. */
    record StreamStarted(String turnId, long startedAt) {
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

    /** Le poste du projet a refusé un appel (F-97 / SF-97-02) ; {@code at} en ms, heure serveur. */
    record StreamRunnerOffline(String hostId, long at) {
    }

    record StreamError(String error) {
    }
}
