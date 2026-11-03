package fr.claudegateway.jarvis;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.jarvis.budget.JarvisBudgetService;

/**
 * <b>Le compteur visible de Jarvis</b> (F-239 / SF-239-12) : {@code GET /api/jarvis/costs} — aujourd'hui et le mois en
 * cours, en euros, par poste, pour le seul compte courant ({@link CurrentUser}).
 *
 * <p>F-244 / SF-244-04 (D10) : la ventilation complète (conversation rapide, voix, transcription, recherches Opus,
 * courrier, agenda) et l'état du plafond de Jarvis (jauge), dans la même réponse.</p>
 */
@RestController
public class JarvisCostController {

    private final JarvisCostService service;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;
    private JarvisBudgetService budget;

    public JarvisCostController(JarvisCostService service, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.service = service;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    @Autowired(required = false)
    public void setBudget(JarvisBudgetService budget) {
        this.budget = budget;
    }

    /** Aujourd'hui, le mois, et le plafond (ou {@code null} si illisible). */
    public record CostsView(JarvisCostService.Costs today, JarvisCostService.Costs month,
            JarvisBudgetService.Status budget) {
    }

    @GetMapping("/jarvis/costs")
    public CostsView costs() {
        atelierAccess.requireAccess();
        java.util.UUID userId = currentUser.requireId();
        JarvisCostService.Summary summary = service.costs(userId);
        JarvisBudgetService.Status status = null;
        if (budget != null) {
            try {
                status = budget.status(userId);
            } catch (RuntimeException e) {
                status = null;
            }
        }
        return new CostsView(summary.today(), summary.month(), status);
    }
}
