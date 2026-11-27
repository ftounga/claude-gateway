package fr.claudegateway.quota.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * <b>La facture AWS Bedrock</b> (F-279 / SF-279-01) : ce que le compte AWS a facturé par jour pour Bedrock, en
 * dollars. Interface séparée de {@link ProviderBillingReport} (Anthropic) : deux fournisseurs, deux contrats, et les
 * tests peuvent doubler l'une sans l'autre.
 */
public interface BedrockBillingReport {

    /** La relecture est autorisée par la configuration (coupe-circuit). */
    boolean enabled();

    /**
     * Le montant facturé par jour sur {@code [from, to)}, en dollars, jours sans dépense omis.
     *
     * @throws Unavailable si la facture ne peut pas être lue (droit, identifiants, réseau)
     */
    List<BilledDay> billedCosts(LocalDate from, LocalDate to);

    /** Un jour facturé, en dollars. */
    record BilledDay(LocalDate day, BigDecimal amountUsd) {
    }

    /** Pourquoi la facture n'a pas pu être lue — dit tel quel à l'écran. */
    enum Reason {
        DISABLED("relecture désactivée (APP_COST_BEDROCK_BILLING_ENABLED=false)"),
        NO_CREDENTIALS("identifiants AWS introuvables"),
        DENIED("droit ce:GetCostAndUsage manquant sur le rôle du backend (voir docs/ops/bedrock-second-canal.md)"),
        UNREACHABLE("Cost Explorer injoignable");

        private final String label;

        Reason(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** La facture n'a pas pu être lue. Le message ne porte jamais le corps de la réponse ni un identifiant. */
    class Unavailable extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final Reason reason;

        public Unavailable(Reason reason) {
            super("Facture AWS illisible : " + reason.label() + ".");
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}
