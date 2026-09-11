# Mini-spec — SF-66-02 · L'essai dure les quatorze jours annoncés

## Identifiant

`F-66 / SF-66-02`

## Feature parente

`F-66` — L'essai tient sa promesse (cadrage : `docs/features/F-66/F-66-cadrage.md`)

## Statut

`done` — PR #371, mergée le 2026-09-11

## Date de création

2026-09-11

## Branche Git

`feat/SF-66-02-quatorze-jours`

---

## Objectif

Porter la durée de l'essai gratuit de **5 à 14 jours** — la durée que le produit annonce
publiquement depuis toujours — et **exposer cette durée dans l'API** pour que l'écran cesse de la
réciter de mémoire.

---

## Pourquoi dans ce sens, et pourquoi maintenant

Le raisonnement est écrit en toutes lettres dans le cadrage, et il est contre-intuitif : **le risque
financier d'un essai n'est pas porté par sa durée mais par son quota**. Un essai vaut 200 000 jetons,
soit ≈ 1,80 $ de coût fournisseur au maximum depuis le décompte pondéré de F-63 — quel que soit le
style d'usage. Quatorze jours ne consomment pas plus que cinq : ils laissent seulement plus de temps
pour dépenser **le même plafond**. On aligne donc le code sur la promesse.

Et « le même plafond » n'est vrai que **depuis SF-66-01** (mergée), qui a refermé la seule fuite :
le plafond repartait à zéro au 1er du mois. C'est la raison d'ordonnancement, pas une préférence.

---

## Comportement attendu

### Cas nominal

1. `app.billing.trial-days` vaut **14** (`APP_BILLING_TRIAL_DAYS` reste le levier d'environnement).
2. Un essai provisionné à l'inscription se termine **14 jours** après sa création
   (`trial_ends_at = création + 14 j`).
3. Le défaut de code de `BillingProperties` vaut **14** lui aussi : une configuration absente ou
   invalide sert la durée **annoncée**, jamais une durée plus courte.
4. `GET /api/billing/subscription` porte un champ **`trialDays`** : la durée réellement servie par
   ce serveur. L'écran l'affiche au lieu d'un littéral — c'est le mécanisme qui empêche l'écart de
   revenir (SF-66-03).
5. Les essais **déjà en cours** gardent leur `trial_ends_at` : aucune reprise de données, aucune
   migration. Un essai commencé la veille finit comme il a commencé.

### Cas d'erreur

| # | Situation | Comportement attendu |
|---|---|---|
| E1 | `APP_BILLING_TRIAL_DAYS` absent | Défaut de code : **14 jours**. |
| E2 | `APP_BILLING_TRIAL_DAYS` ≤ 0 ou non numérique | Même défaut : **14 jours**, sans exception au démarrage (garde déjà présente dans `BillingProperties`). |
| E3 | Abonnement sans essai (payant, résilié) | `trialDays` est renvoyé quand même : c'est une **propriété de l'offre d'essai**, pas de l'abonnement. L'écran l'utilise pour présenter l'offre gratuite à qui n'en bénéficie plus. |

---

## Critères d'acceptation

1. Un essai provisionné se termine à `création + 14 jours` (et non + 5).
2. Le défaut de `BillingProperties.trialDays()` vaut **14** pour `null`, `0` et une valeur négative.
3. `application.yml` sert `14` par défaut, l'environnement restant prioritaire.
4. `GET /api/billing/subscription` expose `trialDays: 14` avec la configuration par défaut, et la
   valeur configurée sinon.
5. Aucun autre montant ni quota n'est modifié : `trial-tokens` reste à **200 000**.
6. Aucune migration, aucune reprise des essais en cours.

---

## Plan de test minimal

### Unitaires

- `SubscriptionServiceTest` : défaut à 14 pour `null` / `0` / négatif ; essai provisionné à
  `création + 14 j` ; une durée configurée (ex. 7) est respectée.

### Intégration

- `BillingApiIntegrationTest` (ou test dédié) : `GET /api/billing/subscription` rend `trialDays` et
  une `trialEndsAt` à quatorze jours de la création.

### Isolation utilisateur

- Inchangée : l'endpoint lit l'abonnement du `userId` du contexte de sécurité, jamais un paramètre
  client. Test de non-régression existant conservé.

---

## Tables / endpoints / composants impactés

### Tables

Aucune. `subscriptions.trial_ends_at` existe déjà et n'est pas repris.

### Endpoints

`GET /api/billing/subscription` — **ajout** du champ `trialDays` (entier). Ajout compatible :
aucun champ retiré ni renommé.

### Composants

| Composant | Changement |
|---|---|
| `backend/src/main/resources/application.yml` | `app.billing.trial-days` : `5` → `14`. |
| `billing/BillingProperties` | Défaut de code `5` → `14`, javadoc réécrite (elle citait `PROJECT.md` §11.10 pour une valeur que la page d'accueil contredisait). |
| `billing/dto/SubscriptionResponse` | Champ `trialDays` ajouté et documenté. |
| `billing/BillingController` | Joint `billingProperties.trialDays()` à la projection (la propriété y est déjà injectée). |

---

## Préoccupation transversale — **Plans / limites** (déclenchée)

Un changement de durée d'essai touche ce qui **ouvre** le quota, pas ce qui le **borne**. Composants
vérifiés :

| Composant | Effet |
|---|---|
| `SubscriptionService.provisionTrial` | Seul point de calcul de `trial_ends_at`. Change de valeur, pas de logique. |
| `EntitlementService.hasActiveTrial` / `resolveMonthlyTokenQuota` | Lisent `trial_ends_at`, jamais la durée : inchangés. |
| `QuotaWindowService` (SF-66-01) | Utilise `trial-days` **uniquement** en repli quand `created_at` manque ; la fenêtre reste bornée au mois précédent quelle que soit la durée. |
| `QuotaService` (pré-vol, jauge), `QuotaAlertService` | Aucun usage de la durée. |
| Écrans (`landing`, `billing`) | Traités en SF-66-03 — c'est l'objet même de la subfeature suivante. |

---

## Contraintes de validation

| Élément | Contrainte | Source |
|---|---|---|
| Durée de l'essai | **14 jours** — la valeur annoncée publiquement | `frontend/src/app/landing/`, `docs/marketing.md`, `PRODUCT_SPEC.md` F-66 |
| Jetons d'essai | **200 000**, inchangé | `docs/TARIFS.md` §4 |
| Dimensionnement de l'essai (faut-il monter à 500 000 ?) | **À CONFIRMER PAR LE PO** — non tranché ici | `OQ-16` point 9 |

---

## Hors périmètre

- Changer `trial-tokens` (200 000) ou tout autre montant commercial.
- Les écrans (SF-66-03).
- Exiger une carte bancaire, rendre l'essai payant, prolonger un essai à la demande.
- Reprendre les essais en cours pour les allonger rétroactivement.
