# Mini-spec — [F-21 / SF-21-03] Bouton « Racheter des tokens » sur /billing

## Identifiant — Parente — Statut

`F-21 / SF-21-03` — `F-21` Rachat de tokens (top-up) — `ready`

## Date — Branche

2026-07-03 — `feat/SF-21-03-topup-button`

---

## Objectif

Exposer sur l'écran `/billing` un bouton **« Racheter des tokens »**, visible **quand le quota de la
période est atteint**, qui lance le paiement one-shot d'un pack de tokens (SF-21-02) et redirige vers
Stripe.

---

## Comportement attendu

### Cas nominal

1. Au chargement de `/billing`, le composant charge le catalogue de packs (`GET /api/billing/topups`).
2. Lorsque `quotaReached(usage)` est vrai (consommation ≥ quota), une section « Racheter des tokens »
   s'affiche dans la carte Consommation, listant le(s) pack(s) (libellé + nombre de tokens) avec un
   bouton d'achat.
3. Un clic sur le bouton appelle `POST /api/billing/topup/checkout {packCode}` puis **redirige** vers
   `checkoutUrl` (même patron que la souscription F-09).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Chargement des packs échoue | Section top-up masquée ; l'écran reste fonctionnel (pas d'erreur bloquante) |
| `topup/checkout` échoue (503 / autre) | Snackbar d'erreur ; pas de redirection ; état du bouton réinitialisé |
| Quota non atteint | La section « Racheter des tokens » n'est **pas** affichée |

---

## Critères d'acceptation

- [ ] La section « Racheter des tokens » n'apparaît que lorsque `quotaReached(usage)` est vrai.
- [ ] Chaque pack affiche son libellé et son nombre de tokens ; le bouton lance le checkout et redirige vers `checkoutUrl`.
- [ ] Un échec de `topup/checkout` affiche un snackbar et ne redirige pas ; le bouton redevient actionnable.
- [ ] Un échec de chargement des packs ne casse pas l'écran (section simplement masquée).
- [ ] UI conforme `DESIGN_SYSTEM.md` (tokens `--cg-*`, Angular Material, badges, `MatSnackBar`, espacements 4px).

---

## Périmètre

### Hors scope (explicite)

- Backend (endpoints, webhook, crédit) : livré en SF-21-02.
- Historique d'achats de tokens, sélection avancée multi-packs / remises : hors V1.

---

## Technique

### Endpoints consommés (contrat figé SF-21-02)

| Méthode | URL | Usage |
|---------|-----|-------|
| GET | `/api/billing/topups` | catalogue des packs `{code,label,tokens}` |
| POST | `/api/billing/topup/checkout` | `{packCode}` → `{checkoutUrl}` |

### Composants Angular

- `BillingService` — `getTopUps()`, `startTopUpCheckout(packCode)`.
- `billing.models.ts` — `TopUpPack`, `TopUpPacksResponse`, `TopUpCheckoutRequest`.
- `BillingComponent` — signal `topUpPacks`, `topUpInProgress` ; méthode `buyTopUp(packCode)` ;
  affichage conditionné par `quotaReached(usage)`.
- `billing.component.html` / `.scss` — section « Racheter des tokens ».

---

## Préoccupation transversale — Navigation / routing & Plans

| Composant | Impact | Vérification |
|-----------|--------|--------------|
| `BillingComponent` (route `/billing` existante) | Ajout d'une section conditionnelle, pas de nouvelle route ni guard | Tests composant + build ; navigation inchangée |
| `redirect()` | Réutilisé pour la redirection Stripe top-up | Test : redirection appelée avec `checkoutUrl` |
| Gate quota | La section dépend de `quotaReached(usage)` (helper existant, inchangé) | Tests : visible/masqué selon usage |

Aucune route ni guard ajouté/modifié : la navigation existante n'est pas affectée.

---

## Plan de test

### Tests composant (Karma/Jasmine)

- [ ] Charge les packs à l'init (`getTopUps` appelé) ; `topUpPacks()` peuplé.
- [ ] `buyTopUp(code)` appelle `startTopUpCheckout(code)` et `redirect(checkoutUrl)`.
- [ ] Échec de `topup/checkout` → pas de redirection ; `topUpInProgress()` réinitialisé.
- [ ] Échec de `getTopUps` → `topUpPacks()` vide, l'écran reste chargé (abonnement/plans OK).

### Tests service

- [ ] `getTopUps()` → `GET /api/billing/topups`.
- [ ] `startTopUpCheckout('STANDARD')` → `POST /api/billing/topup/checkout` avec `{packCode:'STANDARD'}`.

### Isolation utilisateur

- [x] Non applicable côté frontend — l'isolation est garantie côté backend (JWT / `user_id`). Le frontend ne porte aucun identifiant.

---

## Dépendances

### Subfeatures bloquantes

- `SF-21-02` — statut : done (endpoints `topups` / `topup/checkout`).

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **Arbitrage UX (réversible)** : la section « Racheter des tokens » est placée **dans la carte
  Consommation**, sous la barre de quota, et n'apparaît qu'au quota atteint — au plus près du signal
  « Quota atteint » déjà affiché. Un seul pack V1 (`STANDARD`) → un bouton ; le gabarit boucle sur les
  packs pour rester extensible.
- Réutilise le patron de redirection Stripe de F-09 (`redirect()`), aucune dépendance directe à Stripe
  côté frontend (le front n'appelle que `/api/...`).
</content>
