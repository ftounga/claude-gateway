# F-43 — Facturation annuelle · découpage en subfeatures

## Le problème

`fr.claudegateway.billing.BillingPeriod` ne connaît que `MONTHLY` et `DAILY`. Aucun engagement long
n'est proposable : ni la trésorerie encaissée d'avance, ni la rétention qui va avec.

## La cible

La période `YEARLY` s'ajoute, avec un price Stripe annuel par plan
(`STRIPE_PRICE_SOLO_YEARLY`, `STRIPE_PRICE_PRO_YEARLY`, `STRIPE_PRICE_GOLD_YEARLY`) et des prix
d'affichage annuels en variables d'environnement. Remise par défaut : **dix mois payés sur douze**
(≈ −17 %), soit 240 / 990 / 1990 € pour des mensuels à 24 / 99 / 199 €.

## La règle qui ne bouge pas

**Le quota reste MENSUEL.** L'engagement est annuel, l'allocation reste mensuelle. Un quota annuel
changerait la nature du produit : un abonné Pro pourrait consommer 60 M de jetons le premier mois
puis rester onze mois sans rien payer de plus. `QuotaService.currentPeriodStart()` (premier jour du
mois calendaire UTC) et `EntitlementService.resolveMonthlyTokenQuota` ne sont **pas** touchés — et
chaque subfeature qui approche la périodicité le prouve par un test de non-régression.

## Anomalie corrigée au passage

Le plan `DAILY` (Pass journée) a un quota (500 k) et un price ID Stripe, mais **aucun prix
d'affichage** : `display-prices` ne porte que SOLO / PRO / GOLD / BYOK. L'écran de facturation
n'affiche donc **rien** pour cette offre — une carte sans prix à côté d'un bouton d'achat.
`STRIPE_DISPLAY_PRICE_DAILY` (défaut `9`) est ajouté en SF-43-01.

## Subfeatures

| Id | Titre | Portée | Effort |
|----|-------|--------|--------|
| **SF-43-01** | Le prix annuel au catalogue | Backend — enum `YEARLY`, configuration des prices et prix d'affichage annuels, `STRIPE_DISPLAY_PRICE_DAILY`, exposition par `GET /billing/plans` | ~0,5 j |
| **SF-43-02** | Souscrire à l'année | Backend — période portée par le checkout et le changement de plan, persistance `subscriptions.billing_period`, webhook, preuve que le quota reste mensuel | ~1,5 j |
| **SF-43-03** | La bascule mensuel / annuel | Frontend — sélecteur de périodicité sur l'écran de facturation, économie affichée, prix du Pass journée enfin visible | ~1 j |

Chaque subfeature est livrable seule : SF-43-01 n'ouvre aucune souscription annuelle (elle ne fait
qu'exposer un prix), SF-43-02 la rend souscriptible par l'API, SF-43-03 la rend cliquable.

## Hors périmètre de la feature entière

- La **migration automatique** des abonnés mensuels vers l'annuel.
- Le **prorata** de changement de période en cours d'année (Stripe proratise déjà côté fournisseur ;
  aucune règle métier maison n'est ajoutée).
- Le quota annuel, sous quelque forme que ce soit.
- L'offre BYOK à l'année : le mapping accepte n'importe quel plan, mais seuls SOLO / PRO / GOLD sont
  câblés en variables d'environnement.
