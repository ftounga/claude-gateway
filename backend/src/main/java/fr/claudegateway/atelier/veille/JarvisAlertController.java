package fr.claudegateway.atelier.veille;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.veille.JarvisWatchDtos.JarvisAlertView;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Les alertes des veilles</b> (F-230 / SF-230-03) — le contrat que lit Jarvis (F-231 / SF-231-05). L'identité
 * vient <b>exclusivement</b> du {@link CurrentUser} ; une alerte d'un autre compte est introuvable (404).
 */
@RestController
@RequestMapping("/jarvis/alerts")
public class JarvisAlertController {

    private final JarvisAlertService service;
    private final CurrentUser currentUser;

    public JarvisAlertController(JarvisAlertService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    /** Les alertes les plus récentes du compte. */
    @GetMapping
    public List<JarvisAlertView> list(@RequestParam(name = "limit", required = false) Integer limit) {
        return service.recent(currentUser.requireId(), limit);
    }

    @GetMapping("/{alertId}")
    public JarvisAlertView get(@PathVariable UUID alertId) {
        return service.get(currentUser.requireId(), alertId);
    }

    /** Le PO a ouvert l'alerte (mesure d'adoption, D8) ; idempotent. */
    @PostMapping("/{alertId}/open")
    public ResponseEntity<Void> open(@PathVariable UUID alertId) {
        service.open(currentUser.requireId(), alertId);
        return ResponseEntity.noContent().build();
    }
}
