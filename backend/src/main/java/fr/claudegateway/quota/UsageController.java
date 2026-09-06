package fr.claudegateway.quota;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.quota.dto.QuotaAlertResponse;
import fr.claudegateway.quota.dto.UsageReportResponse;
import fr.claudegateway.quota.dto.UsageResponse;

/**
 * Endpoint de consultation de la consommation (F-10). L'identité provient exclusivement du
 * {@link CurrentUser} (JWT) : l'isolation {@code user_id} est appliquée dans le service, jamais
 * depuis un paramètre client. Aucune logique métier ici (controllers fins).
 */
@RestController
@RequestMapping("/usage")
public class UsageController {

    private final QuotaService quotaService;
    private final UsageReportService usageReportService;
    private final QuotaAlertService quotaAlertService;
    private final CurrentUser currentUser;

    public UsageController(
            QuotaService quotaService,
            UsageReportService usageReportService,
            QuotaAlertService quotaAlertService,
            CurrentUser currentUser) {
        this.quotaService = quotaService;
        this.usageReportService = usageReportService;
        this.quotaAlertService = quotaAlertService;
        this.currentUser = currentUser;
    }

    /** Consommation de tokens de l'utilisateur courant pour la période de facturation en cours. */
    @GetMapping
    public UsageResponse usage() {
        UUID userId = currentUser.requireId();
        return UsageResponse.from(quotaService.currentUsage(userId));
    }

    /**
     * Rapport d'usage &amp; coût (F-16) : historique mensuel de consommation et coût estimé de
     * l'utilisateur courant, plus les totaux sur la fenêtre. Lecture seule, isolation {@code user_id}.
     */
    @GetMapping("/report")
    public UsageReportResponse report() {
        UUID userId = currentUser.requireId();
        return UsageReportResponse.from(usageReportService.buildReport(userId));
    }

    /**
     * Alerte de consommation (F-42) : l'utilisateur courant a-t-il franchi le seuil de sa période,
     * et avec quel pack peut-il recharger ? Lecture seule, isolation {@code user_id}.
     */
    @GetMapping("/alert")
    public QuotaAlertResponse alert() {
        UUID userId = currentUser.requireId();
        return QuotaAlertResponse.from(quotaAlertService.currentAlert(userId));
    }

    /**
     * Écarte l'alerte de consommation de la période courante (F-42) : elle ne sera plus présentée
     * avant le mois suivant. Sans effet si aucune alerte n'est levée.
     */
    @PostMapping("/alert/dismiss")
    public ResponseEntity<Void> dismissAlert() {
        UUID userId = currentUser.requireId();
        quotaAlertService.dismissCurrentAlert(userId);
        return ResponseEntity.noContent().build();
    }
}
