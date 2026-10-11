package fr.claudegateway.atelier.plume;

import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.ai.AIProviderException;
import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Plume</b> (F-193 / SF-193-01) : {@code POST /workspaces/{id}/enrich-prompt}, appelé par l'écran
 * quand l'utilisateur clique « ✨ Enrichir ». Un appel, seulement sur ce geste ; rien n'est envoyé à
 * l'agent — la demande enrichie revient dans la saisie, à relire.
 */
@RestController
@RequestMapping("/workspaces/{id}/enrich-prompt")
public class PlumeController {

    private static final Logger log = LoggerFactory.getLogger(PlumeController.class);

    private final PlumeService service;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public PlumeController(PlumeService service, AtelierAccessService atelierAccess, CurrentUser currentUser) {
        this.service = service;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    @PostMapping
    public ResponseEntity<?> enrich(@PathVariable UUID id,
            @RequestBody(required = false) EnrichPromptRequest body) {
        atelierAccess.requireTerminalAccess(id);
        String text = body == null ? null : body.text();
        if (text == null || text.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Saisissez d'abord une demande à enrichir."));
        }
        if (text.length() > PlumeService.MAX_TEXT_CHARS) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Demande trop longue pour être enrichie (4 000 caractères au plus)."));
        }
        try {
            return ResponseEntity.ok(service.enrich(currentUser.requireId(), id, text));
        } catch (PlumeDisabledException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "Plume est désactivé sur cette installation."));
        } catch (PlumeEmptyOutputException | AIProviderException ex) {
            // Le message du fournisseur n'est pas relayé : il peut porter des détails d'appel.
            log.warn("Enrichissement indisponible ({})", ex.getClass().getSimpleName());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(Map.of("error", "L'enrichissement a échoué. Votre texte est intact, réessayez."));
        }
    }
}
