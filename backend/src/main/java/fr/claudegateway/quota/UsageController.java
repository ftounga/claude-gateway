package fr.claudegateway.quota;

import java.util.UUID;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
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
    private final CurrentUser currentUser;

    public UsageController(QuotaService quotaService, CurrentUser currentUser) {
        this.quotaService = quotaService;
        this.currentUser = currentUser;
    }

    /** Consommation de tokens de l'utilisateur courant pour la période de facturation en cours. */
    @GetMapping
    public UsageResponse usage() {
        UUID userId = currentUser.requireId();
        return UsageResponse.from(quotaService.currentUsage(userId));
    }
}
