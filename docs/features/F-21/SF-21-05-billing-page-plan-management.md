# SF-21-05 — Page Billing : plan courant + upgrade/downgrade

Parent : **F-21 — Facturation / rachat de tokens**
Type : subfeature backend + frontend
Statut : En cours

## Objectif (une phrase)

Offrir une page **Facturation** « comme legalcase » : afficher le **plan en cours** (statut, période, quota) et permettre de **souscrire / upgrader / downgrader** entre les offres, ainsi que d'acheter les packs de tokens.

## Comportement nominal

1. **Plan en cours** : statut (essai / actif / annulé), plan, fin de période, quota mensuel du plan.
2. **Cartes d'offres** (SOLO, PRO) avec **prix** et **tokens/mois** :
   - Aucun abonnement payant → bouton **Souscrire** (checkout Stripe existant).
   - Abonnement actif → **Passer à** (upgrade) / **Revenir à** (downgrade) via mise à jour de l'abonnement Stripe (proratisation), + marqueur **Plan actuel**.
3. **Packs de tokens** (Pass journée / Recharge) : achat one-shot (existant).
4. Retours `?checkout=success|cancel` gérés (message).

## Cas d'erreur

| Cas | Réponse |
|-----|---------|
| Change de plan sans abonnement actif (encore en essai) | **409** — souscrire d'abord (checkout) |
| Plan inconnu / sans price configuré | **400** |
| Fournisseur non configuré | **503** (existant) |

## Critères d'acceptation

- [ ] `GET /billing/plans` n'expose que les plans **avec un price configuré** (SOLO, PRO ; pas DAILY), enrichis de `tokens` (quota) + `priceEur` (affichage).
- [ ] `POST /billing/subscription/change {planCode}` met à jour l'abonnement Stripe existant (proratisation) ; 409 si pas d'abonnement actif.
- [ ] `BillingProvider.changeSubscriptionPlan` ajouté (impl Stripe : retrieve subscription, update item vers le nouveau price, `proration_behavior=create_prorations`) + stub de test.
- [ ] Page `/billing` : plan courant + cartes upgrade/downgrade/souscrire + packs.
- [ ] Isolation `user_id` : le change de plan n'agit que sur l'abonnement de l'utilisateur courant.

## Plan de test minimal

- Backend : `changePlan` sur abonnement actif → provider appelé avec le bon price ; sans abonnement actif → 409 ; plan inconnu → 400. `PlansResponse` filtré + enrichi (tokens/prix). Stub provider mis à jour.
- Frontend : affichage plan courant ; bouton upgrade appelle `changePlan` ; souscrire (sans sub) appelle checkout ; specs sur mock HTTP.

## Composants impactés

- **Backend** : `BillingProvider` (+`changeSubscriptionPlan`), `StripeBillingProvider`, stub test ; `SubscriptionService` (+`changePlan`) ; `BillingController` (+`POST /subscription/change`) ; `PlanResponse`/`PlansResponse` (enrichis) ; `PlanCatalog`/config (prix d'affichage `app.billing.plan-display-prices`, filtrage prix configuré) ; `ChangePlanRequest` dto.
- **Frontend** : `billing.service` (+`changePlan`), `billing.models` (Plan enrichi + change), `billing.component` (.ts/.html/.scss).
- **Aucune migration.**

## Hors périmètre

- Annulation d'abonnement en self-service (peut venir après).
- Facture PDF / historique de paiements.
- Péremption 24 h du Pass journée.
