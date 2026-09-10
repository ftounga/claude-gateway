package fr.claudegateway.billing.provider;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.StripeObject;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;

import fr.claudegateway.billing.BillingPeriod;
import fr.claudegateway.billing.BillingProperties;
import fr.claudegateway.billing.PlanCode;

/**
 * Implémentation Stripe du {@link BillingProvider} (F-09). Seule classe à connaître le SDK Stripe :
 * elle crée les sessions Checkout et traduit les webhooks signés en {@link BillingEvent} normalisés.
 *
 * <p>Sécurité : la clé secrète est passée par requête via {@link RequestOptions} (jamais via l'état
 * statique global), n'est jamais journalisée ; la signature du webhook est vérifiée avant toute
 * lecture du payload (PROJECT.md §11.14).</p>
 */
@Component
public class StripeBillingProvider implements BillingProvider {

    private static final Logger log = LoggerFactory.getLogger(StripeBillingProvider.class);

    /** Métadonnée qui distingue une session/abonnement d'option Atelier (F-40) d'un plan. */
    private static final String ATELIER_OPTION_KIND = "atelier_option";

    private final BillingProperties.Stripe config;

    public StripeBillingProvider(BillingProperties properties) {
        this.config = properties.stripe();
    }

    @Override
    public boolean isConfigured() {
        return config.isConfigured();
    }

    @Override
    public CheckoutSession createCheckoutSession(CheckoutCommand command) {
        if (!config.isConfigured()) {
            throw new BillingProviderUnavailableException("Fournisseur de paiement non configuré.");
        }
        if (!StringUtils.hasText(command.priceId())) {
            throw new BillingProviderUnavailableException(
                    "Aucun price ID configuré pour le plan " + command.plan().code() + ".");
        }

        // Le mode se lit sur la périodicité ACHETÉE (F-43), pas sur celle du catalogue : un plan
        // mensuel acheté à l'année reste un abonnement, un pass journée reste un paiement unique.
        SessionCreateParams.Mode mode = command.period() == BillingPeriod.DAILY
                ? SessionCreateParams.Mode.PAYMENT
                : SessionCreateParams.Mode.SUBSCRIPTION;

        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .setMode(mode)
                .setSuccessUrl(config.successUrl())
                .setCancelUrl(config.cancelUrl())
                .setClientReferenceId(command.userId().toString())
                .putMetadata("userId", command.userId().toString())
                .putMetadata("planCode", command.plan().code().name())
                // La périodicité voyage en métadonnée jusqu'au webhook, comme le code de plan :
                // c'est le paiement confirmé qui l'inscrit sur l'abonnement, jamais le clic.
                .putMetadata("billingPeriod", command.period().name())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(command.priceId())
                        .setQuantity(1L)
                        .build());

        // Réutilise le client Stripe existant si connu, sinon pré-remplit l'email.
        if (StringUtils.hasText(command.existingCustomerId())) {
            builder.setCustomer(command.existingCustomerId());
        } else if (StringUtils.hasText(command.customerEmail())) {
            builder.setCustomerEmail(command.customerEmail());
        }

        try {
            RequestOptions options = RequestOptions.builder()
                    .setApiKey(config.secretKey())
                    .build();
            com.stripe.model.checkout.Session session =
                    com.stripe.model.checkout.Session.create(builder.build(), options);
            return new CheckoutSession(session.getUrl(), session.getId());
        } catch (StripeException ex) {
            // On ne journalise ni la clé ni le détail brut : message métier neutre.
            log.warn("Échec de création de la session de paiement Stripe");
            throw new BillingProviderException("Échec de création de la session de paiement.", ex);
        }
    }

    @Override
    public CheckoutSession createTopUpCheckoutSession(TopUpCheckoutCommand command) {
        if (!config.isConfigured()) {
            throw new BillingProviderUnavailableException("Fournisseur de paiement non configuré.");
        }
        if (!StringUtils.hasText(command.priceId())) {
            throw new BillingProviderUnavailableException(
                    "Aucun price ID configuré pour le pack " + command.packCode() + ".");
        }

        // Rachat de tokens = paiement unique (jamais d'abonnement).
        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setSuccessUrl(config.successUrl())
                .setCancelUrl(config.cancelUrl())
                .setClientReferenceId(command.userId().toString())
                .putMetadata("userId", command.userId().toString())
                .putMetadata("kind", "topup")
                .putMetadata("topupCode", command.packCode())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(command.priceId())
                        .setQuantity(1L)
                        .build());

        if (StringUtils.hasText(command.existingCustomerId())) {
            builder.setCustomer(command.existingCustomerId());
        } else if (StringUtils.hasText(command.customerEmail())) {
            builder.setCustomerEmail(command.customerEmail());
        }

        try {
            RequestOptions options = RequestOptions.builder()
                    .setApiKey(config.secretKey())
                    .build();
            com.stripe.model.checkout.Session session =
                    com.stripe.model.checkout.Session.create(builder.build(), options);
            return new CheckoutSession(session.getUrl(), session.getId());
        } catch (StripeException ex) {
            // On ne journalise ni la clé ni le détail brut : message métier neutre.
            log.warn("Échec de création de la session de rachat de tokens Stripe");
            throw new BillingProviderException("Échec de création de la session de paiement.", ex);
        }
    }

    @Override
    public CheckoutSession createAtelierOptionCheckoutSession(AtelierOptionCheckoutCommand command) {
        if (!config.isConfigured()) {
            throw new BillingProviderUnavailableException("Fournisseur de paiement non configuré.");
        }
        if (!StringUtils.hasText(command.priceId())) {
            throw new BillingProviderUnavailableException(
                    "Aucun price ID configuré pour l'option Forge.");
        }

        // L'option est un ABONNEMENT mensuel, distinct de celui du plan : le mode ne dépend d'aucun
        // catalogue, et l'abonnement créé porte ses propres métadonnées — sans elles, les événements
        // d'abonnement ultérieurs seraient indiscernables de ceux du plan.
        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setSuccessUrl(config.successUrl())
                .setCancelUrl(config.cancelUrl())
                .setClientReferenceId(command.userId().toString())
                .putMetadata("userId", command.userId().toString())
                .putMetadata("kind", ATELIER_OPTION_KIND)
                .setSubscriptionData(SessionCreateParams.SubscriptionData.builder()
                        .putMetadata("userId", command.userId().toString())
                        .putMetadata("kind", ATELIER_OPTION_KIND)
                        .build())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(command.priceId())
                        .setQuantity(1L)
                        .build());

        if (StringUtils.hasText(command.existingCustomerId())) {
            builder.setCustomer(command.existingCustomerId());
        } else if (StringUtils.hasText(command.customerEmail())) {
            builder.setCustomerEmail(command.customerEmail());
        }

        try {
            RequestOptions options = RequestOptions.builder()
                    .setApiKey(config.secretKey())
                    .build();
            com.stripe.model.checkout.Session session =
                    com.stripe.model.checkout.Session.create(builder.build(), options);
            return new CheckoutSession(session.getUrl(), session.getId());
        } catch (StripeException ex) {
            // On ne journalise ni la clé ni le détail brut : message métier neutre.
            log.warn("Échec de création de la session de souscription à l'option Atelier");
            throw new BillingProviderException("Échec de création de la session de paiement.", ex);
        }
    }

    @Override
    public OffsetDateTime scheduleSubscriptionCancellation(String providerSubscriptionId) {
        if (!config.isConfigured()) {
            throw new BillingProviderUnavailableException("Fournisseur de paiement non configuré.");
        }
        if (!StringUtils.hasText(providerSubscriptionId)) {
            throw new BillingProviderUnavailableException("Abonnement manquant pour la résiliation.");
        }
        try {
            RequestOptions options = RequestOptions.builder()
                    .setApiKey(config.secretKey())
                    .build();
            com.stripe.model.Subscription subscription =
                    com.stripe.model.Subscription.retrieve(providerSubscriptionId, options);
            // Fin de période, jamais immédiat : le mois est payé, il est dû jusqu'au bout.
            com.stripe.model.Subscription updated = subscription.update(
                    com.stripe.param.SubscriptionUpdateParams.builder()
                            .setCancelAtPeriodEnd(true)
                            .build(),
                    options);
            return toOffsetDateTime(updated.getCurrentPeriodEnd());
        } catch (StripeException ex) {
            // On ne journalise ni la clé ni le détail brut : message métier neutre.
            log.warn("Échec de la programmation de résiliation d'abonnement Stripe");
            throw new BillingProviderException("Échec de la résiliation.", ex);
        }
    }

    @Override
    public void changeSubscriptionPlan(ChangePlanCommand command) {
        if (!config.isConfigured()) {
            throw new BillingProviderUnavailableException("Fournisseur de paiement non configuré.");
        }
        if (!StringUtils.hasText(command.stripeSubscriptionId()) || !StringUtils.hasText(command.newPriceId())) {
            throw new BillingProviderUnavailableException("Abonnement ou price ID manquant pour le changement de plan.");
        }
        try {
            RequestOptions options = RequestOptions.builder()
                    .setApiKey(config.secretKey())
                    .build();
            com.stripe.model.Subscription subscription =
                    com.stripe.model.Subscription.retrieve(command.stripeSubscriptionId(), options);
            // Un abonnement V1 ne porte qu'un seul item (un plan) : on remplace son price par le cible.
            String itemId = subscription.getItems().getData().get(0).getId();
            com.stripe.param.SubscriptionUpdateParams params = com.stripe.param.SubscriptionUpdateParams.builder()
                    .addItem(com.stripe.param.SubscriptionUpdateParams.Item.builder()
                            .setId(itemId)
                            .setPrice(command.newPriceId())
                            .build())
                    .setProrationBehavior(
                            com.stripe.param.SubscriptionUpdateParams.ProrationBehavior.CREATE_PRORATIONS)
                    .build();
            subscription.update(params, options);
        } catch (StripeException ex) {
            // On ne journalise ni la clé ni le détail brut : message métier neutre.
            log.warn("Échec du changement de plan d'abonnement Stripe");
            throw new BillingProviderException("Échec du changement de plan.", ex);
        }
    }

    @Override
    public BillingEvent parseWebhookEvent(String payload, String signatureHeader) {
        if (!StringUtils.hasText(config.webhookSecret())) {
            throw new BillingProviderUnavailableException("Secret de webhook non configuré.");
        }
        if (!StringUtils.hasText(signatureHeader)) {
            throw new WebhookVerificationException("En-tête de signature absent.");
        }

        Event event;
        try {
            event = Webhook.constructEvent(payload, signatureHeader, config.webhookSecret());
        } catch (SignatureVerificationException ex) {
            log.warn("Signature de webhook Stripe invalide");
            throw new WebhookVerificationException("Signature de webhook invalide.");
        }

        return switch (event.getType()) {
            case "checkout.session.completed" -> fromCheckoutSession(event);
            case "customer.subscription.created", "customer.subscription.updated" ->
                    fromSubscription(event, BillingEventType.SUBSCRIPTION_UPDATED);
            case "customer.subscription.deleted" ->
                    fromSubscription(event, BillingEventType.SUBSCRIPTION_DELETED);
            default -> BillingEvent.unhandled();
        };
    }

    private BillingEvent fromCheckoutSession(Event event) {
        Optional<StripeObject> object = event.getDataObjectDeserializer().getObject();
        if (object.isEmpty() || !(object.get() instanceof com.stripe.model.checkout.Session session)) {
            return BillingEvent.unhandled();
        }
        String metaUserId = session.getMetadata() != null ? session.getMetadata().get("userId") : null;
        UUID userId = parseUserId(session.getClientReferenceId(), metaUserId);
        String kind = session.getMetadata() != null ? session.getMetadata().get("kind") : null;

        // Option Atelier (F-40) : distinguée par la métadonnée kind=atelier_option. L'identifiant
        // d'abonnement porté ici est celui de l'OPTION, jamais celui du plan.
        if (ATELIER_OPTION_KIND.equals(kind)) {
            return new BillingEvent(
                    BillingEventType.ATELIER_OPTION_COMPLETED,
                    userId,
                    session.getCustomer(),
                    session.getSubscription(),
                    null,
                    "active",
                    null,
                    event.getId(),
                    null);
        }

        // Rachat de tokens (top-up, F-21) : distingué par la métadonnée kind=topup.
        if ("topup".equals(kind)) {
            String topupCode = session.getMetadata().get("topupCode");
            return new BillingEvent(
                    BillingEventType.TOPUP_COMPLETED,
                    userId,
                    session.getCustomer(),
                    null,
                    null,
                    null,
                    null,
                    event.getId(),
                    topupCode);
        }

        PlanCode planCode = session.getMetadata() != null
                ? parsePlanCode(session.getMetadata().get("planCode"))
                : null;
        BillingPeriod billingPeriod = session.getMetadata() != null
                ? parseBillingPeriod(session.getMetadata().get("billingPeriod"))
                : null;
        return new BillingEvent(
                BillingEventType.CHECKOUT_COMPLETED,
                userId,
                session.getCustomer(),
                session.getSubscription(),
                planCode,
                "active",
                null,
                event.getId(),
                null,
                billingPeriod);
    }

    private BillingEvent fromSubscription(Event event, BillingEventType type) {
        Optional<StripeObject> object = event.getDataObjectDeserializer().getObject();
        if (object.isEmpty() || !(object.get() instanceof com.stripe.model.Subscription subscription)) {
            return BillingEvent.unhandled();
        }
        UUID userId = parseUserId(null,
                subscription.getMetadata() != null ? subscription.getMetadata().get("userId") : null);
        PlanCode planCode = subscription.getMetadata() != null
                ? parsePlanCode(subscription.getMetadata().get("planCode"))
                : null;
        String kind = subscription.getMetadata() != null ? subscription.getMetadata().get("kind") : null;

        // Option Atelier (F-40) : un SECOND abonnement chez le fournisseur. Le traiter comme celui
        // du plan écraserait le plan — résilier l'option annulerait l'abonnement. La métadonnée est
        // posée à la création de l'abonnement (subscription_data), donc dès le premier événement.
        BillingEventType effectiveType = ATELIER_OPTION_KIND.equals(kind)
                ? (type == BillingEventType.SUBSCRIPTION_DELETED
                        ? BillingEventType.ATELIER_OPTION_DELETED
                        : BillingEventType.ATELIER_OPTION_UPDATED)
                : type;

        return new BillingEvent(
                effectiveType,
                userId,
                subscription.getCustomer(),
                subscription.getId(),
                ATELIER_OPTION_KIND.equals(kind) ? null : planCode,
                subscription.getStatus(),
                toOffsetDateTime(subscription.getCurrentPeriodEnd()),
                event.getId(),
                null);
    }

    private static UUID parseUserId(String clientReferenceId, String metadataUserId) {
        String raw = StringUtils.hasText(clientReferenceId) ? clientReferenceId : metadataUserId;
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * Périodicité portée par une métadonnée (F-43). Une valeur absente ou illisible rend
     * {@code null} — et {@code null} signifie « l'événement ne dit rien », donc « ne change rien » :
     * une métadonnée corrompue ne doit jamais réécrire l'engagement d'un abonné.
     */
    private static BillingPeriod parseBillingPeriod(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return BillingPeriod.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static PlanCode parsePlanCode(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        try {
            return PlanCode.valueOf(raw);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static OffsetDateTime toOffsetDateTime(Long epochSeconds) {
        if (epochSeconds == null || epochSeconds <= 0) {
            return null;
        }
        return OffsetDateTime.ofInstant(Instant.ofEpochSecond(epochSeconds), ZoneOffset.UTC);
    }
}
