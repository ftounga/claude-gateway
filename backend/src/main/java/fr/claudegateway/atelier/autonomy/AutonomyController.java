package fr.claudegateway.atelier.autonomy;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Un chantier en autonome</b> (F-219 / SF-219-02) : lancer, reprendre la main, lire l'état. Contrat
 * documenté dans {@code docs/features/F-219/CADRAGE-F-219-chantier-en-autonome.md} — F-220 le réutilise.
 *
 * <p>L'identité vient <b>exclusivement</b> du {@link CurrentUser} ; l'isolation est appliquée par le service
 * ({@code requireOwned} d'abord : 404 sur un terminal d'autrui). Aucune logique ici.</p>
 */
@RestController
public class AutonomyController {

    private final AutonomyService service;
    private final CurrentUser currentUser;

    public AutonomyController(AutonomyService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    /** Les chantiers ouverts du sujet : préflight, course en cours ou dernière, estimation. */
    @GetMapping("/workspaces/{workspaceId}/journey/autonomy")
    public AutonomyService.Board board(@PathVariable UUID workspaceId) {
        return service.board(currentUser.requireId(), workspaceId);
    }

    /** [Lancer en autonome] le chantier N. */
    @PostMapping("/workspaces/{workspaceId}/journey/chantiers/{number}/autonomy")
    @ResponseStatus(HttpStatus.CREATED)
    public AutonomyService.RunView launch(@PathVariable UUID workspaceId, @PathVariable int number) {
        return service.launch(currentUser.requireId(), workspaceId, number);
    }

    /** [Reprendre la main] sur le chantier autonome du sujet. */
    @PostMapping("/workspaces/{workspaceId}/journey/autonomy/stop")
    public AutonomyService.RunView stop(@PathVariable UUID workspaceId) {
        return service.stop(currentUser.requireId(), workspaceId);
    }

    /** Les courses du compte, tous sujets : en cours d'abord, puis les 20 dernières (F-220). */
    @GetMapping("/atelier/autonomy/runs")
    public List<AutonomyService.RunView> runs() {
        return service.runs(currentUser.requireId());
    }
}
