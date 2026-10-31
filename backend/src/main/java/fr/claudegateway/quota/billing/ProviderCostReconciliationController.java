package fr.claudegateway.quota.billing;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>Facturé par le fournisseur / calculé</b> (F-224 / SF-224-02) — l'écran d'administration du coût. Administrateur
 * seul (garde dans le service). Aucune logique ici.
 */
@RestController
@RequestMapping("/admin/cost/reconciliation")
public class ProviderCostReconciliationController {

    private final ProviderCostReconciliationService service;

    public ProviderCostReconciliationController(ProviderCostReconciliationService service) {
        this.service = service;
    }

    /** Les {@code days} derniers jours (1 à 31), du plus récent au plus ancien. */
    @GetMapping
    public ProviderCostReconciliationService.Reconciliation view(@RequestParam(defaultValue = "30") int days) {
        return service.view(days);
    }

    /** [Relire maintenant] : relit la facture chez le fournisseur (sans clé : rien), puis rend le rapprochement. */
    @PostMapping("/refresh")
    public ProviderCostReconciliationService.Reconciliation refresh(@RequestParam(defaultValue = "30") int days) {
        service.refreshNow(days);
        return service.view(days);
    }
}
