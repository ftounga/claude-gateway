package fr.claudegateway.quota;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.quota.dto.CostAlertResponse;

/**
 * Les alertes de dépense <b>de l'utilisateur courant</b> (F-133 / SF-133-12), pour la Forge.
 *
 * <p><b>Ouverte à tout utilisateur authentifié</b>, et c'est la première route de F-133 dans ce
 * cas. SF-133-06 les avait réservées à la console d'administration — un écran que l'on n'ouvre pas
 * en travaillant, si bien qu'une alerte n'alertait personne.</p>
 *
 * <p>L'isolation ne repose donc plus sur le rôle mais sur {@code user_id} : un compte ne voit que
 * ses propres postes. Et les <b>montants</b> sont retirés à la sortie pour qui n'est pas
 * administrateur.</p>
 */
@RestController
@RequestMapping("/cost/alerts")
public class CostAlertController {

    private final CostAlertService alertService;
    private final TurnCostView costView;
    private final CurrentUser currentUser;

    public CostAlertController(CostAlertService alertService, TurnCostView costView,
            CurrentUser currentUser) {
        this.alertService = alertService;
        this.costView = costView;
        this.currentUser = currentUser;
    }

    /** Les alertes de la semaine en cours, sur les postes de l'appelant. */
    @GetMapping("/mine")
    public List<CostAlertResponse> mine() {
        boolean admin = costView.callerIsAdmin();
        return alertService.currentWeekForOwner(currentUser.requireId()).stream()
                .map(alert -> CostAlertResponse.from(alert, admin))
                .toList();
    }
}
