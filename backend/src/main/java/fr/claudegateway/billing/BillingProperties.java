package fr.claudegateway.billing;

import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Configuration du module billing (F-09). Toutes les valeurs sont externalisées ; les secrets Stripe
 * (clé secrète, secret de webhook) proviennent exclusivement de l'environnement et ne sont jamais
 * journalisés (patron identique à la clé Anthropic).
 *
 * @param trialDays durée de l'essai gratuit en jours (défaut <b>14</b>, F-66). C'est la durée que le
 *                  produit annonce publiquement — page d'accueil et {@code docs/marketing.md} — et
 *                  le défaut de code la sert désormais lui aussi : une configuration absente ne doit
 *                  pas servir un essai plus court que la promesse. Allonger l'essai ne coûte rien de
 *                  plus, parce que ce qui borne le coût est le <b>quota</b>
 *                  ({@code app.quota.trial-tokens}, ≈ 1,80 $ au plus depuis F-63), et que ce quota
 *                  est désormais une enveloppe unique sur toute la durée de l'essai (F-66 /
 *                  {@link fr.claudegateway.quota.QuotaWindowService}), non un plafond mensuel.
 * @param stripe    réglages du fournisseur de paiement Stripe (SF-09-02)
 */
@ConfigurationProperties(prefix = "app.billing")
public record BillingProperties(
        Integer trialDays,
        @NestedConfigurationProperty Stripe stripe) {

    public BillingProperties {
        if (trialDays == null || trialDays <= 0) {
            trialDays = 14;
        }
        if (stripe == null) {
            stripe = new Stripe(
                    null, null, Map.of(), Map.of(), null, null, Map.of(), null, null,
                    Map.of(), Map.of(), Map.of(), null, null, null, null);
        }
    }

    /**
     * Réglages Stripe. Le mapping {@code prices} associe un code de plan ({@link PlanCode}) à un
     * price ID Stripe (défini par environnement, jamais en dur — OQ-07).
     *
     * @param secretKey     clé secrète Stripe (env {@code STRIPE_SECRET_KEY}) — vide => fournisseur dormant (503)
     * @param webhookSecret secret de vérification de signature webhook (env {@code STRIPE_WEBHOOK_SECRET})
     * @param prices        code de plan → price ID Stripe
     * @param topupPrices   code de pack de tokens (top-up, F-21) → price ID Stripe
     * @param successUrl    URL de retour après paiement réussi
     * @param cancelUrl     URL de retour après annulation
     * @param displayPrices code de plan → montant d'affichage EUR (cosmétique, SF-21-05)
     * @param atelierOptionPriceId     price ID de l'<b>option Atelier</b> (F-40) — vide => option non souscriptible
     * @param atelierOptionDisplayPrice montant d'affichage EUR de l'option Atelier (cosmétique, défaut 40)
     * @param yearlyPrices        code de plan → price ID Stripe <b>annuel</b> (F-43) — vide => pas d'engagement annuel
     * @param yearlyDisplayPrices code de plan → montant d'affichage EUR <b>annuel</b> (cosmétique, F-43)
     * @param topupDisplayPrices  code de pack de recharge → montant d'affichage EUR (cosmétique, F-67).
     *                            Même patron que {@code displayPrices} : le débit réel appartient au
     *                            price ID, jamais à ce montant. Un pack sans entrée ici est vendable
     *                            <b>sans prix affiché</b> — l'écran le dit, il n'invente rien.
     * @param atelierOptionByokPriceId     price ID de l'option Forge <b>portée par BYOK</b> (F-107 /
     *                                     SF-107-01) — vide par défaut => option non souscriptible
     *                                     sur BYOK. Le prix de l'option dépend du plan porteur : BYOK
     *                                     n'a aucune marge sur les jetons pour porter la plateforme.
     * @param atelierOptionByokDisplayPrice montant d'affichage EUR de l'option Forge sur BYOK
     *                                     (cosmétique, défaut 70)
     * @param vigieOptionPriceId      price ID de l'<b>option Vigie</b> (F-107 / SF-107-03), même price sur
     *                                tout plan porteur — vide par défaut => option non souscriptible
     * @param vigieOptionDisplayPrice montant d'affichage EUR de l'option Vigie (cosmétique, défaut 69,
     *                                décidé par le PO le 2026-09-13)
     */
    public record Stripe(
            String secretKey,
            String webhookSecret,
            Map<String, String> prices,
            Map<String, String> topupPrices,
            String successUrl,
            String cancelUrl,
            Map<String, String> displayPrices,
            String atelierOptionPriceId,
            String atelierOptionDisplayPrice,
            Map<String, String> yearlyPrices,
            Map<String, String> yearlyDisplayPrices,
            Map<String, String> topupDisplayPrices,
            String atelierOptionByokPriceId,
            String atelierOptionByokDisplayPrice,
            String vigieOptionPriceId,
            String vigieOptionDisplayPrice) {

        /**
         * Constructeur d'avant F-107 / SF-107-03 : sans option Vigie (price vide, montant par défaut).
         */
        public Stripe(
                String secretKey,
                String webhookSecret,
                Map<String, String> prices,
                Map<String, String> topupPrices,
                String successUrl,
                String cancelUrl,
                Map<String, String> displayPrices,
                String atelierOptionPriceId,
                String atelierOptionDisplayPrice,
                Map<String, String> yearlyPrices,
                Map<String, String> yearlyDisplayPrices,
                Map<String, String> topupDisplayPrices,
                String atelierOptionByokPriceId,
                String atelierOptionByokDisplayPrice) {
            this(secretKey, webhookSecret, prices, topupPrices, successUrl, cancelUrl, displayPrices,
                    atelierOptionPriceId, atelierOptionDisplayPrice, yearlyPrices, yearlyDisplayPrices,
                    topupDisplayPrices, atelierOptionByokPriceId, atelierOptionByokDisplayPrice, null, null);
        }

        /**
         * Constructeur d'avant F-107 : l'option n'avait qu'un plan porteur tarifaire. Conservé pour
         * que la configuration de Solo/Pro reste construisible telle quelle ; les clés BYOK y sont
         * absentes (price vide, montant par défaut).
         */
        public Stripe(
                String secretKey,
                String webhookSecret,
                Map<String, String> prices,
                Map<String, String> topupPrices,
                String successUrl,
                String cancelUrl,
                Map<String, String> displayPrices,
                String atelierOptionPriceId,
                String atelierOptionDisplayPrice,
                Map<String, String> yearlyPrices,
                Map<String, String> yearlyDisplayPrices,
                Map<String, String> topupDisplayPrices) {
            this(secretKey, webhookSecret, prices, topupPrices, successUrl, cancelUrl, displayPrices,
                    atelierOptionPriceId, atelierOptionDisplayPrice, yearlyPrices, yearlyDisplayPrices,
                    topupDisplayPrices, null, null, null, null);
        }

        @ConstructorBinding
        public Stripe {
            if (prices == null) {
                prices = Map.of();
            }
            if (topupPrices == null) {
                topupPrices = Map.of();
            }
            if (displayPrices == null) {
                displayPrices = Map.of();
            }
            if (yearlyPrices == null) {
                yearlyPrices = Map.of();
            }
            if (yearlyDisplayPrices == null) {
                yearlyDisplayPrices = Map.of();
            }
            if (topupDisplayPrices == null) {
                topupDisplayPrices = Map.of();
            }
            if (successUrl == null || successUrl.isBlank()) {
                successUrl = "http://localhost:4200/billing?checkout=success";
            }
            if (cancelUrl == null || cancelUrl.isBlank()) {
                cancelUrl = "http://localhost:4200/billing?checkout=cancel";
            }
            if (atelierOptionDisplayPrice == null || atelierOptionDisplayPrice.isBlank()) {
                // Le défaut de la feature (40 €/mois) vit aussi ici : une configuration incomplète
                // ne doit pas afficher un prix vide à côté d'un bouton d'achat.
                atelierOptionDisplayPrice = "40";
            }
            if (atelierOptionByokDisplayPrice == null || atelierOptionByokDisplayPrice.isBlank()) {
                // Décidé par le PO le 2026-09-13 (F-107 §9) : BYOK + Forge = 29 + 70 = 99 €.
                atelierOptionByokDisplayPrice = "70";
            }
            if (vigieOptionDisplayPrice == null || vigieOptionDisplayPrice.isBlank()) {
                // Décidé par le PO le 2026-09-13 (F-107 §9) : 69 €, même prix sur Solo, Pro, BYOK et Gold.
                vigieOptionDisplayPrice = "69";
            }
        }

        /** Vrai si une clé secrète est configurée (fournisseur réellement appelable). */
        public boolean isConfigured() {
            return secretKey != null && !secretKey.isBlank();
        }

        /** Price ID Stripe associé au plan, ou {@code null} si non configuré. */
        public String priceId(PlanCode code) {
            return prices == null ? null : prices.get(code.name());
        }

        /** Price ID Stripe associé à un pack de tokens (top-up), ou {@code null} si non configuré. */
        public String topupPriceId(String packCode) {
            return topupPrices == null ? null : topupPrices.get(packCode);
        }

        /**
         * Montant d'affichage (EUR) d'un pack de recharge (F-67), ou {@code null} si aucun montant
         * n'est configuré pour ce pack.
         *
         * <p>Le {@code null} est la réponse <b>voulue</b> quand la configuration est muette : il vaut
         * mieux qu'un écran dise « prix indiqué au paiement » que d'afficher un montant que personne
         * n'a décidé. Une valeur blanche est donc traitée comme une absence — sans quoi elle
         * traverserait l'API et s'afficherait « €» à côté d'un bouton d'achat.</p>
         */
        public String topupDisplayPrice(String packCode) {
            if (packCode == null || topupDisplayPrices == null) {
                return null;
            }
            String price = topupDisplayPrices.get(packCode);
            return price == null || price.isBlank() ? null : price;
        }

        /** Montant d'affichage (EUR) du plan pour la page de facturation, ou {@code null} si absent. */
        public String displayPrice(PlanCode code) {
            return displayPrices == null ? null : displayPrices.get(code.name());
        }

        /**
         * Price ID Stripe <b>annuel</b> du plan (F-43), ou {@code null} si l'engagement annuel n'est
         * pas configuré pour ce plan.
         */
        public String yearlyPriceId(PlanCode code) {
            return code == null ? null : yearlyPrices.get(code.name());
        }

        /**
         * Montant d'affichage (EUR) <b>annuel</b> du plan (F-43), ou {@code null} si absent.
         * Cosmétique : le débit réel est porté par le price annuel.
         */
        public String yearlyDisplayPrice(PlanCode code) {
            return code == null ? null : yearlyDisplayPrices.get(code.name());
        }

        /**
         * Vrai si ce plan est réellement proposable à l'année (F-43). Trois conditions, et les trois
         * comptent :
         *
         * <ul>
         *   <li>le plan est un <b>abonnement mensuel</b> : un pass journée est un paiement unique
         *       chez le fournisseur, l'annualiser n'aurait aucun sens — et la règle se lit sur la
         *       période native du plan, jamais sur un code de plan écrit en dur ;</li>
         *   <li>un <b>price ID annuel</b> est configuré : sans lui, le paiement répondrait 503 ;</li>
         *   <li>un <b>montant d'affichage annuel</b> est configuré : sans lui, l'écran présenterait
         *       un bouton d'achat sans prix.</li>
         * </ul>
         *
         * <p>Une offre à moitié configurée n'est donc jamais proposée, dans un sens comme dans
         * l'autre.</p>
         */
        public boolean isYearlyAvailable(Plan plan) {
            if (plan == null || plan.period() != BillingPeriod.MONTHLY) {
                return false;
            }
            String priceId = yearlyPriceId(plan.code());
            String displayPrice = yearlyDisplayPrice(plan.code());
            return priceId != null && !priceId.isBlank()
                    && displayPrice != null && !displayPrice.isBlank();
        }

        /**
         * Vrai si l'option Atelier (F-40) est réellement souscriptible : fournisseur configuré
         * <b>et</b> price ID d'option renseigné. Sans les deux, l'option est dormante (503).
         */
        public boolean isAtelierOptionConfigured() {
            return isConfigured() && atelierOptionPriceId != null && !atelierOptionPriceId.isBlank();
        }

        /**
         * Price ID de l'option Forge <b>pour ce plan porteur</b> (F-107 / SF-107-01) : celui de BYOK
         * pour BYOK, celui de Solo/Pro pour tout autre plan.
         */
        public String atelierOptionPriceId(PlanCode carrier) {
            return carrier == PlanCode.BYOK ? atelierOptionByokPriceId : atelierOptionPriceId;
        }

        /** Montant d'affichage EUR de l'option Forge pour ce plan porteur (F-107 / SF-107-01). */
        public String atelierOptionDisplayPrice(PlanCode carrier) {
            return carrier == PlanCode.BYOK ? atelierOptionByokDisplayPrice : atelierOptionDisplayPrice;
        }

        /**
         * Vrai si l'option Vigie (F-107 / SF-107-03) est réellement souscriptible : fournisseur configuré
         * <b>et</b> price ID d'option renseigné. Un seul price, quel que soit le plan porteur (§9 : « même
         * prix partout »).
         */
        public boolean isVigieOptionConfigured() {
            return isConfigured() && vigieOptionPriceId != null && !vigieOptionPriceId.isBlank();
        }

        /**
         * Vrai si l'option Forge est réellement souscriptible <b>sur ce plan porteur</b> : fournisseur
         * configuré et price ID du plan porteur renseigné. Un price BYOK vide rend l'option dormante
         * sur BYOK sans rien changer pour Solo/Pro.
         */
        public boolean isAtelierOptionConfigured(PlanCode carrier) {
            String priceId = atelierOptionPriceId(carrier);
            return isConfigured() && priceId != null && !priceId.isBlank();
        }
    }
}
