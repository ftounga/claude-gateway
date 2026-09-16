package fr.claudegateway.activity;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.activity.dto.RevenueResponse;
import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;

/**
 * Suivi d'activité et de revenu (F-124) — <b>cumul & total</b> (SF-124-02) : le revenu cumulé par
 * poste et le total tous clients, avec la répartition déclaré/supposé.
 *
 * <p>Endpoint <b>JWT</b> gardé par le droit Forge. L'identité vient du {@link CurrentUser} ; le calcul
 * est agrégé par {@code user_id} — jamais les postes ou les CRA d'un autre.</p>
 */
@RestController
@RequestMapping("/activity")
public class RevenueController {

    private final RevenueService revenueService;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public RevenueController(RevenueService revenueService, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.revenueService = revenueService;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    /** Le cumul de revenu de l'utilisateur : par poste, et le total tous clients. */
    @GetMapping("/revenue")
    public RevenueResponse revenue() {
        atelierAccess.requireRunnerAccess();
        UUID userId = currentUser.requireId();
        return RevenueResponse.from(revenueService.compute(userId));
    }
}
