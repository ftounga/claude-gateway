package fr.claudegateway.jarvis.trace;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Le journal de l'écoute</b> (F-233 / SF-233-15) : l'écran Jarvis déclare les transitions et erreurs de sa boucle
 * d'écoute ({@code POST /api/jarvis/listen-trace}, 204). Ouvert à la portée compagnon. Aucune route de lecture : le
 * journal se lit en SQL, filtré par {@code user_id} (audit du 2026-11-07).
 */
@RestController
public class JarvisListenTraceController {

    private final JarvisListenTraceService service;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public JarvisListenTraceController(JarvisListenTraceService service, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.service = service;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    @PostMapping("/jarvis/listen-trace")
    public ResponseEntity<Void> record(@RequestBody(required = false) JarvisListenTraceService.Batch body) {
        atelierAccess.requireAccess();
        service.record(currentUser.requireId(), body);
        return ResponseEntity.noContent().build();
    }
}
