package fr.claudegateway.billing;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Traduit une périodicité <b>demandée par le client</b> en une décision d'achat : la période
 * retenue et le price fournisseur correspondant (F-43 / SF-43-02).
 *
 * <p>Un seul endroit décide, parce que deux chemins d'achat en ont besoin — la souscription
 * ({@link CheckoutService}) et le changement de plan ({@link SubscriptionService}). Dupliquer la
 * règle, c'est accepter qu'elle diverge : le jour où l'un des deux replierait silencieusement vers
 * le mensuel, le client paierait autre chose que ce qu'il a demandé, et seulement sur ce
 * chemin-là.</p>
 */
@Component
public class BillingPeriodSelection {

    private final BillingProperties properties;

    public BillingPeriodSelection(BillingProperties properties) {
        this.properties = properties;
    }

    /**
     * Périodicité retenue pour un achat.
     *
     * @param requested valeur brute fournie par le client ; {@code null} ou vide ⇒ {@link BillingPeriod#MONTHLY}
     * @throws UnknownBillingPeriodException valeur inconnue, ou {@code DAILY} (non achetable)
     */
    public BillingPeriod resolve(String requested) {
        if (!StringUtils.hasText(requested)) {
            // Le contrat d'origine n'envoyait pas de périodicité : il doit continuer de marcher.
            return BillingPeriod.MONTHLY;
        }
        BillingPeriod period;
        try {
            period = BillingPeriod.valueOf(requested.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new UnknownBillingPeriodException("Périodicité inconnue : " + requested);
        }
        if (period == BillingPeriod.DAILY) {
            throw new UnknownBillingPeriodException(
                    "La périodicité journalière n'est pas un choix d'achat : elle est portée par le "
                            + "pass journée lui-même.");
        }
        return period;
    }

    /**
     * Périodicité effectivement achetée pour ce plan.
     *
     * <p>Un pass journée impose la sienne : elle n'est pas un choix d'achat, et le client n'a pas à
     * l'envoyer pour acheter le pass. On accepte donc l'absence de périodicité, mais on refuse un
     * engagement annuel — replier « à l'année » vers un pass de 24 h ferait payer une journée à qui
     * a demandé un an.</p>
     *
     * @throws UnknownBillingPeriodException     périodicité inconnue ou non achetable
     * @throws YearlyBillingUnavailableException annuel demandé sur un plan facturé à l'unité
     */
    public BillingPeriod resolveFor(Plan plan, String requested) {
        BillingPeriod period = resolve(requested);
        if (plan.period() != BillingPeriod.DAILY) {
            return period;
        }
        if (period == BillingPeriod.YEARLY) {
            throw new YearlyBillingUnavailableException(
                    "Le pass journée est un paiement unique : il ne se souscrit pas à l'année.");
        }
        return BillingPeriod.DAILY;
    }

    /**
     * Price fournisseur à facturer pour ce plan à cette périodicité.
     *
     * @throws YearlyBillingUnavailableException l'annuel est demandé mais ce plan n'en propose pas —
     *         jamais de repli silencieux sur le price mensuel
     */
    public String priceId(Plan plan, BillingPeriod period) {
        if (period != BillingPeriod.YEARLY) {
            return properties.stripe().priceId(plan.code());
        }
        if (!properties.stripe().isYearlyAvailable(plan)) {
            throw new YearlyBillingUnavailableException(
                    "L'offre " + plan.code() + " n'est pas proposée à l'année.");
        }
        return properties.stripe().yearlyPriceId(plan.code());
    }
}
