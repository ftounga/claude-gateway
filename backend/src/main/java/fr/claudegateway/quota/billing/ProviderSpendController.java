package fr.claudegateway.quota.billing;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * <b>Les deux pots</b> (F-279 / SF-279-01) — Anthropic et AWS Bedrock, le mois en cours. Administrateur seul (garde
 * dans le service). Aucune logique ici.
 */
@RestController
@RequestMapping("/admin/cost/providers")
public class ProviderSpendController {

    private final ProviderSpendService service;

    public ProviderSpendController(ProviderSpendService service) {
        this.service = service;
    }

    /** Les deux jauges du mois, le total et le canal en service. */
    @GetMapping
    public ProviderSpendService.ProviderSpend view() {
        return service.view();
    }

    /** [Relire maintenant] : relit la facture Anthropic et la facture AWS, puis rend les jauges. */
    @PostMapping("/refresh")
    public ProviderSpendService.ProviderSpend refresh() {
        return service.refreshNow();
    }
}
