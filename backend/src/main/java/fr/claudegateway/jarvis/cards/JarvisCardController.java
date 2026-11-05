package fr.claudegateway.jarvis.cards;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Le plein écran d'une pancarte</b> (F-248 / SF-248-06, D6) — {@code GET /api/jarvis/cards/{id}} : une ligne relue
 * par index sous le compte du principal. 503 coupe-circuit éteint (K7) ; 400 identifiant illisible ; 404 inconnu ou
 * d'un autre compte. Ouvert à la portée {@code jarvis-compagnon} (liste fermée, SF-232-05).
 */
@RestController
public class JarvisCardController {

    private final JarvisCardDetailService service;
    private final JarvisCardKeyLookup keys;
    private final JarvisCardsProperties properties;
    private final CurrentUser currentUser;

    public JarvisCardController(JarvisCardDetailService service, JarvisCardKeyLookup keys,
            JarvisCardsProperties properties, CurrentUser currentUser) {
        this.service = service;
        this.keys = keys;
        this.properties = properties;
        this.currentUser = currentUser;
    }

    /**
     * F-248 / SF-248-08 (§4.8) — {@code GET /api/jarvis/cards?keys=PROJ-123,projet!42[&subjectId=]} : les pancartes des
     * clés citées par Opus, demandées après le premier mot ; 6 clés au plus (le reste ignoré), faits déjà au registre
     * du compte seulement. 503 coupe-circuit ; 400 sans clé reconnue.
     */
    @GetMapping("/jarvis/cards")
    public ResponseEntity<java.util.List<JarvisCard>> byKeys(@RequestParam(required = false) java.util.List<String> keys,
            @RequestParam(required = false) java.util.UUID subjectId) {
        if (!properties.on()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        java.util.List<String> accepted = JarvisCardKeyLookup.accepted(keys);
        if (accepted.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(this.keys.lookup(currentUser.requireId(), accepted, subjectId));
    }

    @GetMapping("/jarvis/cards/{id}")
    public ResponseEntity<JarvisCardDetail> detail(@PathVariable String id) {
        if (!properties.on()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        if (!JarvisCardDetailService.wellFormed(id)) {
            return ResponseEntity.badRequest().build();
        }
        return service.detail(currentUser.requireId(), id).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
