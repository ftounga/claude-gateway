# Mini-spec — SF-66-03 · L'offre d'essai se décrit elle-même

## Identifiant

`F-66 / SF-66-03`

## Feature parente

`F-66` — L'essai tient sa promesse (cadrage : `docs/features/F-66/F-66-cadrage.md`)

## Statut

`done` — PR #372, mergée le 2026-09-11

## Date de création

2026-09-11

## Branche Git

`feat/SF-66-03-offre-essai-se-decrit`

---

## Objectif

Faire dire par l'**API** les deux chiffres qui décrivent l'offre d'essai — sa **durée** et ses
**jetons** — pour qu'aucun écran n'ait plus à les réciter de mémoire.

---

## Pourquoi cette subfeature existe

SF-66-02 a exposé `trialDays` et refermé la moitié du problème. L'autre moitié est encore là : la
carte « Gratuit » de la page de facturation annonce **« 200 000 tokens pour découvrir »** en dur,
à côté de la durée. C'est **exactement le même défaut** que celui que F-66 répare — un chiffre
commercial écrit dans un écran, qui dérive le jour où la configuration change.

Et ce jour est prévisible : `OQ-16` point 9 pose précisément la question de porter l'essai à
500 000 jetons. Si elle est tranchée demain, la carte continuerait d'annoncer 200 000 à côté d'un
essai qui en sert 500 000. Le mécanisme se pose donc **maintenant**, pendant qu'il est gratuit.

**Aucune valeur n'est décidée ici** : `app.quota.trial-tokens` reste à 200 000. On expose ce que la
configuration sert, on n'en change rien.

---

## Comportement attendu

### Cas nominal

1. `GET /api/billing/subscription` porte un champ **`trialTokens`** : l'allocation d'essai servie par
   ce serveur (`app.quota.trial-tokens`), en jetons.
2. Le champ est renvoyé **quel que soit le statut** de l'abonnement — comme `trialDays` (SF-66-02) :
   c'est une propriété de l'**offre** d'essai, pas de l'abonnement de celui qui la consulte. Un
   client payant voit ainsi l'offre gratuite décrite correctement dans la grille.
3. La valeur vient de `QuotaProperties`, déjà injecté dans le controller : aucune nouvelle source,
   aucun calcul.

### Cas d'erreur

| # | Situation | Comportement attendu |
|---|---|---|
| E1 | `app.quota.trial-tokens` absent ou invalide (`null`, négatif) | Défaut de `QuotaProperties` : **200 000**, garde déjà présente. L'API ne renvoie jamais de valeur vide ni négative. |
| E2 | `app.quota.trial-tokens` volontairement à `0` | La valeur `0` est renvoyée telle quelle : c'est une configuration légitime (essai sans jetons), et l'écran doit pouvoir la dire plutôt que l'écran mentir. |

---

## Critères d'acceptation

1. `GET /api/billing/subscription` expose `trialTokens: 200000` avec la configuration par défaut.
2. La valeur suit la configuration : une allocation configurée à 30 000 est rendue telle quelle.
3. Le champ est présent pour un abonnement **payant** comme pour un essai.
4. Aucun montant ni quota modifié : `trial-tokens` reste à 200 000, `trial-days` à 14.
5. Aucune migration, aucun changement de schéma.

---

## Plan de test minimal

### Unitaires

- `SubscriptionResponseTest` : la projection porte `trialDays` et `trialTokens` sans rien perdre des
  champs existants, et n'expose aucun identifiant Stripe.

### Intégration

- `BillingApiIntegrationTest` : `GET /api/billing/subscription` rend `trialTokens` (valeur de test
  configurée) et `trialDays`.

### Isolation utilisateur

- Inchangée : l'endpoint lit l'abonnement du `userId` du contexte de sécurité. Les deux nouveaux
  champs sont des constantes de configuration, identiques pour tous — ils ne portent **aucune**
  donnée d'un autre utilisateur.

---

## Tables / endpoints / composants impactés

### Tables

Aucune.

### Endpoints

`GET /api/billing/subscription` — **ajout** du champ `trialTokens` (entier). Ajout compatible.

### Composants

| Composant | Changement |
|---|---|
| `billing/dto/SubscriptionResponse` | Champ `trialTokens` ajouté et documenté. |
| `billing/BillingController` | Joint `quotaProperties.trialTokens()` à la projection (la propriété y est déjà injectée). |

---

## Préoccupation transversale — **Plans / limites** (déclenchée)

Le champ **décrit** une limite, il n'en **applique** aucune. Vérification :

| Composant | Effet |
|---|---|
| `EntitlementService.resolveMonthlyTokenQuota` | Seul endroit qui **oppose** `trialTokens` : inchangé. |
| `QuotaService`, `QuotaWindowService`, `QuotaAlertService` | Inchangés — aucun ne lit la projection d'API. |
| `GET /billing/plans` | Inchangé : il annonce les quotas des **plans**, l'essai n'en est pas un. |

---

## Hors périmètre

- Changer `trial-tokens` (200 000) : **décision commerciale du PO**, suivie en `OQ-16` point 9.
- Les écrans (SF-66-04).
- Exposer l'essai comme un « plan » du catalogue : il n'en est pas un, et l'y ajouter changerait le
  contrat de `GET /billing/plans` pour tous ses lecteurs.
