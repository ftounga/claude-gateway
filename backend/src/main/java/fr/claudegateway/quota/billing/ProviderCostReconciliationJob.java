package fr.claudegateway.quota.billing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * <b>La relecture quotidienne de la facture</b> (F-224 / SF-224-02) : les 31 derniers jours, chaque matin. Inactive
 * sans clé d'administration. Idempotente (chaque ligne jour × clé est remplacée) : deux pods qui la jouent ne
 * doublent rien. Une panne du fournisseur est journalisée par son type, jamais avec la clé.
 */
@Component
public class ProviderCostReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(ProviderCostReconciliationJob.class);

    private final ProviderCostReconciliationService service;

    public ProviderCostReconciliationJob(ProviderCostReconciliationService service) {
        this.service = service;
    }

    @Scheduled(cron = "${app.cost.anthropic-admin.cron:0 20 6 * * *}")
    public void run() {
        try {
            service.refresh(ProviderCostReconciliationService.MAX_DAYS);
        } catch (RuntimeException e) {
            log.warn("Relecture de la facture du fournisseur en échec ({})", e.getClass().getSimpleName());
        }
    }
}
