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
 * Opus ; {@code fallback} quand le cerveau rapide s'efface (plafond, quota : l'écran garde le mode d'avant) ;
 * {@code done} à la fin ; {@code error} sur une panne de flux.</p>
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
            Long turnSeq) {
    }

    @PostMapping(path = "/jarvis/converse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> converse(@RequestBody(required = false) ConverseRequest request) {
        if (!service.enabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (request == null || request.subjectId() == null || request.text() == null || request.text().isBlank()
                || request.text().length() > MAX_TEXT
                || (request.heardBeforeCut() != null && request.heardBeforeCut().length() > MAX_HEARD)) {
            return ResponseEntity.badRequest().build();
        }
        UUID userId = currentUser.requireId();
        Optional<Workspace> workspace = workspaces.findByIdAndUserId(request.subjectId(), userId);
        if (workspace.isEmpty() || !atelierAccess.hasTerminalAccess(request.subjectId())) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MS);
        JarvisFastBrainService.Request call = new JarvisFastBrainService.Request(userId, workspace.get(),
                request.chantier(), request.text(), request.heardBeforeCut());
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
        try {
            JarvisFastBrainService.Outcome outcome = service.converse(call, delta -> send(emitter, "text",
                    new TextEvent(delta)));
            switch (outcome.kind()) {
                case FALLBACK -> send(emitter, "fallback", new FallbackEvent(outcome.reason()));
                case DELEGATE -> {
                    JarvisFastBrainService.Delegation d = outcome.delegation();
                    send(emitter, "delegated", new DelegatedEvent(d.delegationId(), d.subjectId(), d.chantier(),
                            d.label(), d.annonce(), d.demande(), d.automatic(), d.reason()));
                }
                case ANSWER -> {
                    // La réponse a déjà été poussée au fil de l'eau.
                }
            }
            send(emitter, "done", new DoneEvent(brainOf(outcome), outcome.spokenText(), outcome.firstTokenMs(),
                    outcome.model(), turnSeq, outcome.guards()));
            emitter.complete();
        } catch (RuntimeException e) {
            log.warn("Cerveau rapide : flux interrompu ({})", e.getClass().getSimpleName());
            try {
                send(emitter, "error", new ErrorEvent("fast_brain_failed"));
                emitter.complete();
            } catch (RuntimeException ignored) {
                emitter.completeWithError(e);
            }
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

    record DelegatedEvent(UUID delegationId, UUID subjectId, Integer chantier, String label, String announce,
            String demande, boolean automatic, String reason) {
    }

    record FallbackEvent(String reason) {
    }

    record DoneEvent(String brain, String spokenText, long firstTokenMs, String model, Long turnSeq,
            JarvisFastBrainService.Guards guards) {
    }

    record ErrorEvent(String error) {
    }
}
