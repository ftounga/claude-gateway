package fr.claudegateway.atelier.dashboard;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Le tableau de bord d'un client</b> (F-220 / SF-220-02) : tous les chantiers ouverts des sujets d'un poste.
 * Contrat documenté dans {@code docs/features/F-220/SF-220-02-tableau-du-client.md}.
 *
 * <p>L'identité vient <b>exclusivement</b> du {@link CurrentUser} ; le droit au runner est exigé comme pour les
 * autres routes d'un poste ; l'isolation est appliquée par le service ({@code requireOwned} sur le poste d'abord :
 * 404 sur le poste d'autrui). Aucune logique ici.</p>
 */
@RestController
public class ClientBoardController {

    private final ClientBoardService service;
    private final AtelierAccessService atelierAccess;
    private final CurrentUser currentUser;

    public ClientBoardController(ClientBoardService service, AtelierAccessService atelierAccess,
            CurrentUser currentUser) {
        this.service = service;
        this.atelierAccess = atelierAccess;
        this.currentUser = currentUser;
    }

    /** Les chantiers ouverts du client, groupés par qui les débloque, le plus dormant d'abord. */
    @GetMapping("/runner-hosts/{hostId}/chantiers")
    public ClientBoard board(@PathVariable UUID hostId) {
        atelierAccess.requireRunnerAccess();
        return service.board(currentUser.requireId(), hostId);
    }

    /**
     * [Revoir les attentes de plus de 14 jours] (F-227 / SF-227-01) : PROPOSE la clôture des attentes anciennes du
     * poste ; l'utilisateur confirme chacune ou toutes. {@code days} borné à [1, 365].
     */
    @PostMapping("/runner-hosts/{hostId}/chantiers/stale-tasks/propose")
    public StaleReview proposeStale(@PathVariable UUID hostId, @RequestParam(defaultValue = "14") int days) {
        atelierAccess.requireRunnerAccess();
        int bounded = Math.max(1, Math.min(365, days));
        return new StaleReview(bounded, service.proposeStaleTasks(currentUser.requireId(), hostId, bounded));
    }

    /** Le résultat de la revue : le seuil appliqué et le nombre d'attentes proposées à la clôture. */
    public record StaleReview(int days, int proposed) {
    }
}
