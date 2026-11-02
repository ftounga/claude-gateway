package fr.claudegateway.jarvis.activity;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>La carte d'activité de Jarvis</b> (F-236 / SF-236-26, NF-16) : {@code GET /api/jarvis/activity?days=7&tz=…},
 * lu à la demande par l'écran {@code /jarvis} (jamais pendant un tour). L'identité vient <b>exclusivement</b> du
 * {@link CurrentUser} ; {@code days} est borné à [1, 7], {@code tz} illisible ⇒ Europe/Paris.
 */
@RestController
public class JarvisActivityController {

    private final JarvisActivityService service;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public JarvisActivityController(JarvisActivityService service, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.service = service;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    @GetMapping("/jarvis/activity")
    public JarvisActivity activity(@RequestParam(name = "days", defaultValue = "7") int days,
            @RequestParam(name = "tz", required = false) String tz) {
        atelierAccess.requireRunnerAccess();
        return service.activity(currentUser.requireId(), days, JarvisActivityService.zoneOf(tz));
    }
}
