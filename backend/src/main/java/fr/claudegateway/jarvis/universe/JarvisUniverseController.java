package fr.claudegateway.jarvis.universe;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>L'univers de Jarvis</b> (F-234 / SF-234-01) : {@code GET /api/jarvis/universe}, chargé à l'ouverture de
 * {@code /jarvis} et rafraîchi à chaque fin de tour. L'identité vient <b>exclusivement</b> du {@link CurrentUser}.
 */
@RestController
public class JarvisUniverseController {

    private final JarvisUniverseService service;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public JarvisUniverseController(JarvisUniverseService service, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.service = service;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    @GetMapping("/jarvis/universe")
    public JarvisUniverse universe() {
        atelierAccess.requireRunnerAccess();
        return service.universe(currentUser.requireId());
    }
}
