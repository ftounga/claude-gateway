# Mini-spec — F-09 / SF-09-03 — Écran de facturation (frontend)

## Identifiant

`F-09 / SF-09-03`

## Feature parente

`F-09` — Abonnements & billing Stripe

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-09-03-frontend-billing`

---

## Objectif

> Offrir un écran Angular `/billing` où l'utilisateur voit son abonnement (statut/essai), parcourt
> les plans et lance une souscription via Stripe Checkout, en consommant l'API F-09 (contrats figés
> importés de SF-09-01 et SF-09-02).

---

## Comportement attendu

### Cas nominal

1. À l'ouverture de `/billing` (route protégée par `authGuard`), le composant charge en parallèle
   `GET /api/billing/subscription` et `GET /api/billing/plans`.
2. L'abonnement courant est affiché : badge de statut (Essai / Actif / Annulé…), date de fin d'essai
   ou de période courante.
3. Les plans sont présentés en cartes (libellé, mode Hosted/BYOK, période). Un bouton « Souscrire »
   par plan.
4. Au clic « Souscrire », `POST /api/billing/checkout {planCode}` renvoie `checkoutUrl` ; le
   navigateur est redirigé vers Stripe (`window.location.href = checkoutUrl`).
5. Au retour de Stripe, `/billing?checkout=success|cancel` affiche un `MatSnackBar` d'info et
   recharge l'abonnement.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| `GET subscription`/`plans` échoue | `MatSnackBar` erreur, écran dégradé (pas de crash) |
| `POST checkout` → 503 (`billing_unavailable`) | `MatSnackBar` : « Facturation momentanément indisponible » |
| `POST checkout` → autre erreur | `MatSnackBar` erreur générique |
| Non authentifié | `authGuard` redirige vers `/login` (comportement existant) |

---

## Critères d'acceptation

- [ ] `/billing` est une route lazy protégée par `authGuard`.
- [ ] Le composant appelle `GET /api/billing/subscription` et `GET /api/billing/plans` à l'init.
- [ ] Le statut d'abonnement est affiché avec un badge conforme au design system.
- [ ] Chaque plan affiche un bouton « Souscrire » qui déclenche `POST /api/billing/checkout` puis la redirection vers `checkoutUrl`.
- [ ] `?checkout=success`/`cancel` déclenche un `MatSnackBar` et un rechargement de l'abonnement.
- [ ] Erreurs réseau → `MatSnackBar` (jamais `window.alert/confirm`).
- [ ] Le `BillingService` cible uniquement `/api/...` (jamais un fournisseur externe direct).
- [ ] Couleurs/polices/espacements conformes à `docs/DESIGN_SYSTEM.md`.

---

## Périmètre

### Hors scope (explicite)

- Portail Stripe de gestion (changer de carte, factures) → ultérieur.
- Enforcement/affichage des quotas de tokens → F-10.
- Annulation in-app d'un abonnement → ultérieur (gérée côté Stripe pour l'instant).

---

## Technique

### Contrat API (importé de SF-09-01 / SF-09-02, figé)

- `GET /api/billing/plans` → `{ plans: [{ code, label, providerMode, period }] }`
- `GET /api/billing/subscription` → `{ status, planCode, trialEndsAt, currentPeriodEnd }`
- `POST /api/billing/checkout` `{ planCode }` → `{ checkoutUrl }` (400/401/503)

### Composants Angular

- `BillingComponent` (`/billing`) — écran principal.
- `BillingService` (`core/services`) — `getPlans()`, `getSubscription()`, `startCheckout(planCode)`.
- `billing.models.ts` — `Plan`, `SubscriptionView`, `PlansResponse`, `CheckoutResponse`.
- Route `/billing` ajoutée à `app.routes.ts` (lazy + `authGuard`).

### Tables / endpoints

- Aucun changement backend.

---

## Plan de test

### Tests unitaires (`BillingService`)

- [ ] `getPlans()` GET `/api/billing/plans`.
- [ ] `getSubscription()` GET `/api/billing/subscription`.
- [ ] `startCheckout('PRO')` POST `/api/billing/checkout` body `{planCode:'PRO'}`.

### Tests composant (`BillingComponent`, service mocké)

- [ ] À l'init, charge abonnement + plans (spies appelés, données affichées).
- [ ] `subscribe('PRO')` appelle `startCheckout` et redirige vers `checkoutUrl`.
- [ ] Erreur de checkout → snackbar (pas de redirection).

### Isolation

- [x] Non applicable côté frontend — l'isolation `user_id` est garantie côté backend (JWT). Les tests
  frontend s'exécutent sur mock du service, indépendants du backend mergé.

---

## Dépendances

### Subfeatures bloquantes

- `SF-09-01` (subscription + plans) — **done/mergée**.
- `SF-09-02` (checkout) — **done/mergée**.

### Questions ouvertes impactées

- Aucune (OQ-07 déjà contournée côté backend).

---

## Préoccupation transversale — Navigation / routing

- Ajout d'une **route** `/billing` (lazy, `authGuard`). Composants impactés : `app.routes.ts` (1 entrée).
  Aucune route existante modifiée ni redirigée. La route wildcard `**` reste en dernier.

---

## Notes et décisions

- **Arbitrage — redirection via `window.location.href`** vers l'URL Checkout hébergée : c'est le flux
  Stripe standard (le paiement se fait sur le domaine Stripe). Ce n'est pas une boîte de dialogue
  interdite (`alert/confirm`). Réversible.
- **Arbitrage — pas d'entrée de menu globale ajoutée** : il n'existe pas encore de shell/nav applicatif
  commun (les écrans sont autonomes). La route `/billing` est atteignable par URL et sera reliée au futur
  shell (F-11/F-12). Réversible.
