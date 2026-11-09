package fr.claudegateway.jarvis.fastbrain;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.chat.SseStreamDispatch;

/**
 * <b>Converser avec Jarvis</b> (F-235 / SF-235-01, D4) — {@code POST /api/jarvis/converse}, en SSE.
 *
 * <p>Événements : {@code text} ({@code {delta}}) au fil de la réponse ; {@code delegated} quand la demande part à
 * Opus ; {@code show} (F-237 / SF-237-08) quand il montre une pièce ou navigue ; {@code fallback} quand le cerveau rapide s'efface (plafond, quota : l'écran garde le mode d'avant) ;
 * {@code done} à la fin ; {@code error} sur une panne de flux ; {@code cards} (F-248 / SF-248-02) le jeu des faits lus,
 * jamais avant la première phrase.</p>
 *
 * <p><b>Dormant</b> (D17) : coupe-circuit éteint ⇒ 503, l'écran retombe sur {@code streamChat}. Le sujet est cherché
 * <b>sous l'utilisateur</b> : celui d'un autre compte est introuvable (404). Tout est résolu sur le thread de la
 * requête, où vit le contexte de sécurité ; le relais tourne sur le pool des flux.</p>
 */
@RestController
public class JarvisFastBrainController {

    private static final Logger log = LoggerFactory.getLogger(JarvisFastBrainController.class);
    static final int MAX_TEXT = 4_000;
    static final int MAX_HEARD = 2_000;
    private static final long EMITTER_TIMEOUT_MS = 60_000L;

    private final JarvisFastBrainService service;
    private final WorkspaceRepository workspaces;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;
    private final Executor executor;

    public JarvisFastBrainController(JarvisFastBrainService service, WorkspaceRepository workspaces,
            AtelierAccessService atelierAccess, CurrentUser currentUser,
            @Qualifier("chatStreamExecutor") Executor executor) {
        this.service = service;
        this.workspaces = workspaces;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
        this.executor = executor;
    }

    /** La demande de l'écran. */
    public record ConverseRequest(UUID subjectId, String text, Integer chantier, String heardBeforeCut,
            Long turnSeq, String screen, Boolean presentation) {

        public ConverseRequest(UUID subjectId, String text, Integer chantier, String heardBeforeCut, Long turnSeq) {
            this(subjectId, text, chantier, heardBeforeCut, turnSeq, null, null);
        }

        public ConverseRequest(UUID subjectId, String text, Integer chantier, String heardBeforeCut, Long turnSeq,
                String screen) {
            this(subjectId, text, chantier, heardBeforeCut, turnSeq, screen, null);
        }
    }

    @PostMapping(path = "/jarvis/converse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> converse(@RequestBody(required = false) ConverseRequest request) {
        if (!service.enabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (request == null || request.text() == null || request.text().isBlank()
                || request.text().length() > MAX_TEXT
                || (request.heardBeforeCut() != null && request.heardBeforeCut().length() > MAX_HEARD)) {
            return ResponseEntity.badRequest().build();
        }
        UUID userId = currentUser.requireId();
        // F-235 / SF-235-04 (D19) : Jarvis est global — le sujet courant est facultatif ; s'il est donné, il doit
        // appartenir au compte (404 sinon, sans appel modèle).
        Optional<Workspace> workspace = Optional.empty();
        if (request.subjectId() != null) {
            workspace = workspaces.findByIdAndUserId(request.subjectId(), userId);
            if (workspace.isEmpty() || !atelierAccess.hasTerminalAccess(request.subjectId())) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
            }
        }
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        JarvisFastBrainService.Request call = new JarvisFastBrainService.Request(userId, workspace.orElse(null),
                request.chantier(), request.text(), request.heardBeforeCut(), request.screen(),
                Boolean.TRUE.equals(request.presentation()));
        SseStreamDispatch.submit(executor, emitter, () -> relay(emitter, call, request.turnSeq()));
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(emitter);
    }

    /** La demande de pré-chauffage : le sujet ouvert (facultatif, pour le journal du coût). */
    public record WarmRequest(UUID subjectId) {
    }

    /**
     * Pré-chauffage du cache du cerveau rapide (F-235 / SF-235-02, D6) — à l'ouverture de {@code /jarvis} et au mot
     * d'éveil. 503 éteint ; 204 sinon (parti, ou trop tôt : au plus un toutes les 4 min).
     */
    @PostMapping("/jarvis/converse/warm")
    public ResponseEntity<Void> warm(@RequestBody(required = false) WarmRequest request) {
        if (!service.enabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        UUID userId = currentUser.requireId();
        Workspace workspace = request == null || request.subjectId() == null ? null
                : workspaces.findByIdAndUserId(request.subjectId(), userId).orElse(null);
        service.warmAsync(userId, workspace);
        return ResponseEntity.noContent().build();
    }

    private void relay(SseEmitter emitter, JarvisFastBrainService.Request call, Long turnSeq) {
        // F-248 / SF-248-02 — le catalogue des faits lus de la réplique (coupe-circuit, jamais en présentation).
        fr.claudegateway.jarvis.cards.JarvisCardCatalog catalog = cardsOn(call)
                ? fr.claudegateway.jarvis.cards.JarvisCardCatalog.open() : null;
        // F-252 / SF-252-01 — la préparation serveur de la réplique (null : aucun appel au modèle).
        java.util.concurrent.atomic.AtomicReference<Long> prepMs = new java.util.concurrent.atomic.AtomicReference<>();
        try {
            JarvisFastBrainService.Outcome outcome = service.converse(call, new JarvisFastBrainService.Sink() {
                @Override
                public void text(String delta) {
                    send(emitter, "text", new TextEvent(delta));
                    // D10 — les pancartes partent avec ou après la première phrase, jamais avant.
                    flushCards(emitter, catalog);
                }

                @Override
                public void show(JarvisShowCommand command) {
                    // F-237 / SF-237-08 — un ordre d'écran validé : l'écran l'applique à son réducteur.
                    send(emitter, "show", command);
                }

                @Override
                public void prepared(long ms) {
                    prepMs.compareAndSet(null, ms);
                }

                @Override
                public void understood(String text) {
                    // F-239 / SF-239-05 — la parole relue, affichée à l'écran avant la réponse ; jamais dite.
                    send(emitter, "understood", new UnderstoodEvent(text));
                }
            });
            switch (outcome.kind()) {
                case FALLBACK -> send(emitter, "fallback", new FallbackEvent(outcome.reason()));
                case DELEGATE -> {
                    JarvisFastBrainService.Delegation d = outcome.delegation();
                    send(emitter, "delegated", new DelegatedEvent(d.delegationId(), d.subjectId(), d.chantier(),
                            d.label(), d.annonce(), d.demande(), d.automatic(), d.reason(),
                            isJarvisThread(call.userId(), d.subjectId())));
                }
                case ANSWER -> {
                    // La réponse a déjà été poussée au fil de l'eau.
                }
            }
            // D10 — rien n'a été dit (délégation muette) : le jeu part à la fin de la réponse.
            flushCards(emitter, catalog);
            send(emitter, "done", new DoneEvent(brainOf(outcome), outcome.spokenText(), outcome.firstTokenMs(),
                    outcome.model(), turnSeq, outcome.guards(), budgetOf(outcome),
                    service.portraitInUse(call.userId(), call.presentation()), stateOf(call, outcome),
                    prepMs.get()));
            emitter.complete();
        } catch (RuntimeException e) {
            log.warn("Cerveau rapide : flux interrompu ({})", e.getClass().getSimpleName());
            try {
                send(emitter, "error", new ErrorEvent("fast_brain_failed"));
                emitter.complete();
            } catch (RuntimeException ignored) {
                emitter.completeWithError(e);
            }
        } finally {
            if (catalog != null) {
                fr.claudegateway.jarvis.cards.JarvisCardCatalog.close();
            }
        }
    }

    /** F-248 / SF-248-01 — le coupe-circuit des pancartes (facultatif : sans lui, éteintes). */
    private fr.claudegateway.jarvis.cards.JarvisCardsProperties cards;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCards(fr.claudegateway.jarvis.cards.JarvisCardsProperties cards) {
        this.cards = cards;
    }

    /** Vrai si la réplique tient un catalogue de pancartes : coupe-circuit allumé, hors présentation (K7, §4.6). */
    boolean cardsOn(JarvisFastBrainService.Request call) {
        return cards != null && cards.on() && !call.presentation() && call.userId() != null;
    }

    /** Envoie le jeu de pancartes s'il a changé depuis le dernier envoi ; jamais d'exception vers la réplique. */
    static void flushCards(SseEmitter emitter, fr.claudegateway.jarvis.cards.JarvisCardCatalog catalog) {
        if (catalog == null) {
            return;
        }
        fr.claudegateway.jarvis.cards.JarvisCardCatalog.Event event = catalog.takeIfChanged();
        if (event != null) {
            send(emitter, "cards", event);
        }
    }

    static String brainOf(JarvisFastBrainService.Outcome outcome) {
        return switch (outcome.kind()) {
            case ANSWER -> "FAST";
            case DELEGATE -> "FAST_DELEGATED";
            case FALLBACK -> "OPUS";
        };
    }

    private static void send(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            throw new IllegalStateException("flux fermé", e);
        }
    }

    record TextEvent(String delta) {
    }

    /**
     * @param jarvisThread F-245 / SF-245-02 — la délégation part au fil Jarvis : l'écran ne change pas de « sujet en
     *                     vue » (le fil Jarvis n'en est pas un)
     */
    record DelegatedEvent(UUID delegationId, UUID subjectId, Integer chantier, String label, String announce,
            String demande, boolean automatic, String reason, boolean jarvisThread) {
    }

    /** F-245 / SF-245-02 — le fil Jarvis du compte (facultatif). */
    private fr.claudegateway.jarvis.thread.JarvisThreadService threads;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setThreads(fr.claudegateway.jarvis.thread.JarvisThreadService threads) {
        this.threads = threads;
    }

    /** Vrai si {@code subjectId} est le fil Jarvis du compte (isolation : lu sous le {@code user_id} du principal). */
    boolean isJarvisThread(UUID userId, UUID subjectId) {
        if (threads == null || subjectId == null) {
            return false;
        }
        try {
            return threads.idOf(userId).map(subjectId::equals).orElse(false);
        } catch (RuntimeException e) {
            return false;
        }
    }

    record FallbackEvent(String reason) {
    }

    /** F-239 / SF-239-05 — la parole relue par le cerveau rapide (garde anti-invention passée). */
    record UnderstoodEvent(String text) {
    }

    /**
     * @param budget F-239 / SF-239-11 — {@code soft} (budget du mois dépassé, le cerveau rapide continue), {@code hard}
     *               (plafond dur : réponse fixe), sinon {@code null}
     */
    record DoneEvent(String brain, String spokenText, long firstTokenMs, String model, Long turnSeq,
            JarvisFastBrainService.Guards guards, String budget, boolean portrait,
            fr.claudegateway.jarvis.state.JarvisStateTurnMeter.Measure state,
            // F-252 / SF-252-01 — la préparation serveur (ms), null sans appel au modèle.
            Long prepMs) {
    }

    /** F-247 / SF-247-01 — la mesure d'état de la réplique (facultative). */
    private fr.claudegateway.jarvis.state.JarvisStateTurnMeter stateMeter;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setStateMeter(fr.claudegateway.jarvis.state.JarvisStateTurnMeter stateMeter) {
        this.stateMeter = stateMeter;
    }

    /** F-247 / SF-247-01 — poste éteint, question d'état, réponse ; calculée après la réponse, jamais d'exception. */
    fr.claudegateway.jarvis.state.JarvisStateTurnMeter.Measure stateOf(JarvisFastBrainService.Request call,
            JarvisFastBrainService.Outcome outcome) {
        if (stateMeter == null) {
            return fr.claudegateway.jarvis.state.JarvisStateTurnMeter.NONE;
        }
        return stateMeter.measure(call.userId(), call.workspace(), call.text(), outcome);
    }

    static String budgetOf(JarvisFastBrainService.Outcome outcome) {
        if (JarvisFastBrainService.REASON_BUDGET_SOFT.equals(outcome.reason())) {
            return "soft";
        }
        return JarvisFastBrainService.REASON_BUDGET_HARD.equals(outcome.reason()) ? "hard" : null;
    }

    record ErrorEvent(String error) {
    }
}
