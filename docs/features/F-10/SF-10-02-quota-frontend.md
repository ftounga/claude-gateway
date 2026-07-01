# Mini-spec — [F-10 / SF-02] Affichage de la consommation (frontend)

## Identifiant

`F-10 / SF-02`

## Feature parente

`F-10` — Quotas & entitlements (compteurs de consommation tokens)

## Statut

`ready`

## Date de création

2026-07-01

## Branche Git

`feat/SF-10-02-quota-frontend`

---

## Objectif

> Afficher à l'utilisateur sa consommation de tokens de la période courante (utilisé / quota /
> restant) via une jauge, sur l'écran d'abonnement, en consommant `GET /api/usage`.

---

## Comportement attendu

### Cas nominal

1. À l'ouverture de l'écran **Abonnement & facturation**, le composant appelle `GET /api/usage`.
2. Une carte « Consommation » affiche : tokens utilisés, quota, restants, et une barre de progression
   (part consommée = `usedTokens / quotaTokens`), ainsi que la période (`periodStart` → `periodEnd`).
3. Si `usedTokens ≥ quotaTokens` (quota atteint), la barre est pleine et un badge « Quota atteint »
   (badge d'avertissement) est affiché.

### Contrat importé

Contrat `GET /api/usage` **importé de SF-10-01 (backend)** :

```json
{ "usedTokens": 4200, "quotaTokens": 200000, "remainingTokens": 195800,
  "periodStart": "2026-07-01", "periodEnd": "2026-08-01" }
```

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|----------------------|
| `GET /api/usage` échoue (réseau/5xx) | Notification `MatSnackBar` d'erreur ; la carte reste en état « indisponible », le reste de l'écran fonctionne |
| `quotaTokens = 0` (essai expiré / abonnement inactif) | Barre à 100 %, badge « Quota atteint », 0 restant |
| 401 (session expirée) | Géré globalement par l'`authInterceptor` (redirection /login) — inchangé |

---

## Critères d'acceptation

- [ ] La carte « Consommation » affiche utilisé/quota/restant issus de `GET /api/usage`.
- [ ] La barre de progression reflète `usedTokens / quotaTokens` (bornée 0–100 %, 100 % si quota = 0).
- [ ] Un badge « Quota atteint » apparaît quand `usedTokens ≥ quotaTokens`.
- [ ] Un échec de chargement n'empêche pas l'affichage de l'abonnement et des plans (dégradation gracieuse).
- [ ] Couleurs/polices/espacements conformes à `DESIGN_SYSTEM.md` (tokens `--cg-*`, multiples de 4px).
- [ ] Le service `UsageService` cible bien `/api/usage` (jamais Stripe/Anthropic en direct).

## Contraintes de validation

| Champ | Format | Notes |
|-------|--------|-------|
| pourcentage | 0–100, entier | `min(100, round(used/quota*100))` ; `quota=0 ⇒ 100` |
| nombres de tokens | entier, séparateur de milliers (locale) | via `DecimalPipe`/`toLocaleString` |

---

## Périmètre

### Hors scope (explicite)

- Historique/graphe de consommation dans le temps (V2 — rapports d'usage F-16).
- Gestion de l'erreur 402 côté chat (message d'upgrade sur l'écran de chat) — non requis ici ;
  l'erreur `quota_exceeded` est déjà mappée côté backend.
- Toute logique de calcul de quota (elle vit côté backend, SF-10-01).

---

## Technique

### Endpoint(s) consommé(s)

| Méthode | URL | Auth |
|---------|-----|------|
| GET | `/api/usage` | Oui (JWT via `authInterceptor`) |

### Composants Angular

- `UsageService` (`core/services/usage.service.ts`) — `getUsage(): Observable<UsageView>`.
- `usage.models.ts` (`core/models/`) — interface `UsageView`.
- `BillingComponent` — ajout d'une carte « Consommation » (jauge `mat-progress-bar` + badge).

### Tables impactées

- Aucune (frontend).

---

## Plan de test

### Tests unitaires (sur mock, indépendants du backend)

- [ ] `UsageService` — `getUsage()` fait un GET sur `/api/usage` et mappe la réponse (HttpTestingController).
- [ ] `BillingComponent` — charge et affiche la consommation ; calcule le pourcentage ; badge « Quota atteint » si `used ≥ quota` ; `quota = 0 ⇒ 100 %`.
- [ ] `BillingComponent` — un échec de `getUsage()` déclenche une notification et n'empêche pas l'affichage des plans.

### Isolation utilisateur

- [x] Garantie côté backend (JWT → `user_id`) ; le frontend n'envoie aucun identifiant utilisateur. Non applicable côté composant.

---

## Dépendances

### Subfeatures bloquantes

- `SF-10-01` (backend `GET /api/usage`) — **done** (mergée, PR #26). Contrat figé.

### Questions ouvertes impactées

- Aucune (OQ-08 tranchée en SF-10-01).

---

## Notes et décisions

- Emplacement : la carte « Consommation » est ajoutée à l'écran **Abonnement & facturation**
  (contexte naturel du quota, réutilise le layout existant) — décision réversible, pas de nouvelle
  route ni entrée de navigation.
