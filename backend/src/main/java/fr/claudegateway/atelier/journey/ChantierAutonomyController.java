package fr.claudegateway.atelier.journey;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Un chantier en autonome</b> (F-219) : le contrôle avant départ (SF-219-01). La liste des
 * chantiers d'un sujet, avec course et estimation, est servie par {@code AutonomyController} (SF-219-02). L'API est documentée dans
 * {@code docs/features/F-219/CADRAGE-F-219-chantier-en-autonome.md} — F-220 la réutilise.
 *
 * <p>L'identité vient <b>exclusivement</b> du {@link CurrentUser} ; l'isolation est appliquée par le
 * service ({@code requireOwned} d'abord : 404 sur un terminal d'autrui).</p>
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}/journey")
public class ChantierAutonomyController {

    private final ChantierPreflightService preflights;
    private final CurrentUser currentUser;

    public ChantierAutonomyController(ChantierPreflightService preflights, CurrentUser currentUser) {
        this.preflights = preflights;
        this.currentUser = currentUser;
    }

    /** Le préflight du chantier ouvert N : « Prêt à partir », ou ce qui l'empêche. */
    @GetMapping("/chantiers/{number}/autonomy/preflight")
    public ChantierPreflight preflight(@PathVariable UUID workspaceId, @PathVariable int number) {
        return preflights.one(currentUser.requireId(), workspaceId, number).preflight();
    }
}
