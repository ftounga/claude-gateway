package fr.claudegateway.quota;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.quota.dto.CostBudgetRequest;
import fr.claudegateway.quota.dto.CostBudgetResponse;
import jakarta.validation.Valid;

/**
 * Les budgets hebdomadaires de dépense et leurs alertes (F-133 / SF-133-04 et SF-133-06).
 * <b>Administrateur seul.</b>
 *
 * <p>L'autorisation est appliquée dans {@link CostBudgetService}, au même endroit que la règle
 * métier : une garde posée ici, sur le contrôleur, serait oubliée le jour où un second appelant
 * arrivera par un autre chemin.</p>
 *
 * <p><b>Ces routes ne bloquent rien.</b> Elles posent une consigne de pilotage ; le refus de service
 * reste l'affaire du quota commercial.</p>
 */
@RestController
@RequestMapping("/admin/cost")
public class CostBudgetController {

    private final CostBudgetService service;
    private final CostAlertService alertService;
    private final CurrentUser currentUser;

    public CostBudgetController(CostBudgetService service, CostAlertService alertService,
            CurrentUser currentUser) {
        this.service = service;
        this.alertService = alertService;
        this.currentUser = currentUser;
    }

    /** Le budget par défaut et ceux propres à un client. */
    @GetMapping("/budget")
    public CostBudgetResponse budgets() {
        return CostBudgetResponse.from(service.all(currentUser.requireId()));
    }

    /** Pose ou remplace le budget <b>par défaut</b>. */
    @PutMapping("/budget")
    public CostBudgetResponse setDefault(@Valid @RequestBody CostBudgetRequest request) {
        service.setDefault(currentUser.requireId(), request.amountEur());
        return budgets();
    }

    /** Pose ou remplace le budget d'un <b>client</b>. */
    @PutMapping("/budget/{hostId}")
    public CostBudgetResponse setForHost(@PathVariable UUID hostId,
            @Valid @RequestBody CostBudgetRequest request) {
        service.setForHost(currentUser.requireId(), hostId, request.amountEur());
        return budgets();
    }

    /** Retire le budget propre d'un client : il retombe sur le défaut. */
    @DeleteMapping("/budget/{hostId}")
    public CostBudgetResponse clearForHost(@PathVariable UUID hostId) {
        service.clearForHost(currentUser.requireId(), hostId);
        return budgets();
    }

    /**
     * Les alertes de dépense de la <b>semaine en cours</b> (F-133 / SF-133-06).
     *
     * <p>Calculées à la lecture : elles sont donc toujours justes, et il n'y a rien à « marquer
     * comme vu ». Elles ne bloquent rien et n'envoient rien — elles se lisent quand on regarde.</p>
     */
    @GetMapping("/alerts")
    public List<CostAlert> alerts() {
        return alertService.currentWeek(currentUser.requireId());
    }
}
