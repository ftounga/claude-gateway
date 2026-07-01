package fr.claudegateway.billing;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Réception des webhooks Stripe (F-09 / SF-09-02). Endpoint <b>public</b> (pas de JWT) : l'appelant
 * est authentifié par la <b>signature</b> du payload, vérifiée dans le fournisseur avant toute
 * mutation. Le corps est lu <b>brut</b> (String) car la signature couvre les octets exacts.
 *
 * <p>Répond systématiquement 200 pour les événements vérifiés (gérés ou ignorés) afin d'éviter les
 * rejeux inutiles de Stripe ; une signature invalide remonte en 400 via le handler global.</p>
 */
@RestController
public class StripeWebhookController {

    private final WebhookService webhookService;

    public StripeWebhookController(WebhookService webhookService) {
        this.webhookService = webhookService;
    }

    @PostMapping("/webhook/stripe")
    public ResponseEntity<Void> handle(
            @RequestBody String payload,
            @RequestHeader(name = "Stripe-Signature", required = false) String signature) {
        webhookService.handle(payload, signature);
        return ResponseEntity.ok().build();
    }
}
