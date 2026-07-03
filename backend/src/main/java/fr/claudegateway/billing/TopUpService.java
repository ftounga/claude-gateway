package fr.claudegateway.billing;

import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.claudegateway.billing.provider.BillingProvider;
import fr.claudegateway.billing.provider.CheckoutSession;
import fr.claudegateway.billing.provider.TopUpCheckoutCommand;

/**
 * Orchestration du rachat de tokens (top-up, F-21 / SF-21-02). Résout le pack depuis le catalogue
 * serveur (montant de tokens autoritatif), réutilise l'abonnement de l'utilisateur pour récupérer son
 * éventuel client Stripe existant, résout le price ID de configuration et délègue la création de la
 * session <b>one-shot</b> au {@link BillingProvider} — jamais à Stripe en direct.
 */
@Service
public class TopUpService {

    private final TopUpCatalog topUpCatalog;
    private final SubscriptionService subscriptionService;
    private final BillingProvider billingProvider;
    private final BillingProperties properties;

    public TopUpService(
            TopUpCatalog topUpCatalog,
            SubscriptionService subscriptionService,
            BillingProvider billingProvider,
            BillingProperties properties) {
        this.topUpCatalog = topUpCatalog;
        this.subscriptionService = subscriptionService;
        this.billingProvider = billingProvider;
        this.properties = properties;
    }

    /**
     * Crée une session de paiement one-shot pour le pack de tokens demandé et l'utilisateur courant.
     *
     * @param userId      utilisateur authentifié (contexte de sécurité)
     * @param email       email de l'utilisateur (pré-remplissage Checkout)
     * @param packCodeRaw code de pack fourni par le client
     * @return la session de paiement (URL de redirection)
     * @throws UnknownPlanException code de pack absent/inconnu du catalogue
     */
    public CheckoutSession createTopUpCheckout(UUID userId, String email, String packCodeRaw) {
        TopUpPack pack = topUpCatalog.find(packCodeRaw)
                .orElseThrow(() -> new UnknownPlanException("Pack de tokens inconnu : " + packCodeRaw));
        Subscription subscription = subscriptionService.getOrCreateForUser(userId);
        String priceId = properties.stripe().topupPriceId(pack.code());

        TopUpCheckoutCommand command = new TopUpCheckoutCommand(
                userId, email, subscription.getStripeCustomerId(), pack.code(), priceId);
        return billingProvider.createTopUpCheckoutSession(command);
    }
}
