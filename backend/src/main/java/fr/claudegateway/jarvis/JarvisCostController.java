package fr.claudegateway.jarvis;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Le compteur visible de Jarvis</b> (F-239 / SF-239-12) : {@code GET /api/jarvis/costs} — aujourd'hui et le mois en
 * cours, en euros, par poste, pour le seul compte courant ({@link CurrentUser}).
 */
@RestController
public class JarvisCostController {

    private final JarvisCostService service;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public JarvisCostController(JarvisCostService service, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.service = service;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    @GetMapping("/jarvis/costs")
    public JarvisCostService.Summary costs() {
        atelierAccess.requireAccess();
        return service.costs(currentUser.requireId());
    }
}
