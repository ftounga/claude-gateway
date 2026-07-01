# Mini-spec — [F-16 / SF-16-02] Écran Rapports d'usage & coût (frontend)

## Identifiant

`F-16 / SF-16-02`

## Feature parente

`F-16` — Rapports d'usage & coût in-app

## Statut

`done`

## Date de création

2026-07-02

## Branche Git

`feat/SF-16-02-usage-report-frontend`

---

## Objectif

> Écran `/reports` : tableau de bord de consommation et de coût estimé de l'utilisateur courant
> (cartes de synthèse + historique mensuel + visualisation), alimenté par `GET /api/usage/report`.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur authentifié ouvre `/reports` (route protégée par `authGuard`).
2. Le composant appelle `UsageReportService.getReport()` → `GET /api/usage/report`.
3. Affichage :
   - **Cartes de synthèse** : total de tokens, coût estimé total (avec devise), période courante
     (tokens + coût du mois marqué `current`, ou 0 si absente).
   - **Historique mensuel** : `mat-table` paginée (période, tokens entrée, sortie, total, coût estimé),
     la ligne courante mise en évidence.
   - **Visualisation** : barres CSS légères du total de tokens par période (pas de dépendance graphe).
4. État vide (`periods: []`) : message « Aucune consommation enregistrée » (pas d'erreur).

### Cas d'erreur

| Situation | Comportement attendu | Code |
|-----------|---------------------|------|
| Appel API en échec (5xx/réseau) | `MatSnackBar` d'erreur, état non-bloquant | — |
| Non authentifié | Redirection login via `authGuard` (401 intercepté globalement) | 401 |
| `periods` vide | Message d'état vide, cartes à 0 | 200 |

---

## Critères d'acceptation

- [ ] Route `/reports` (lazy, `canActivate: [authGuard]`) enregistrée.
- [ ] `UsageReportService.getReport()` appelle `GET /api/usage/report` (aucun `user_id` transmis).
- [ ] Cartes de synthèse : total tokens, coût estimé total (devise), période courante.
- [ ] Table paginée (`mat-paginator`) des périodes : période, entrée, sortie, total, coût estimé.
- [ ] État vide géré (message dédié, aucune erreur).
- [ ] Erreur API → `MatSnackBar` (jamais `window.alert`).
- [ ] Design System respecté : palette, polices (Inter/Space Grotesk/JetBrains Mono), espacements 4px,
      Angular Material.
- [ ] Lien d'accès depuis l'écran Réglages.
- [ ] `npm run build` et `npm test` verts.

---

## Périmètre

### Hors scope (explicite)

- Aucun appel backend réel dans les tests (service **mocké**).
- Pas d'export du rapport (relèverait de F-14).
- Pas de sélecteur de plage de dates (fenêtre = `max-months` côté backend).
- Pas de bibliothèque de graphes tierce (visualisation CSS native).

---

## Contraintes de validation

Lecture seule ; aucun formulaire soumis. Le contrat d'entrée est le DTO `UsageReportView`.

---

## Technique

### Contrat consommé (importé de SF-16-01 backend, FIGÉ)

```
GET /api/usage/report  → 200
{
  "currency": "EUR",
  "periods": [
    { "periodStart": "2026-07-01", "periodEnd": "2026-08-01",
      "inputTokens": 12000, "outputTokens": 8000, "totalTokens": 20000,
      "estimatedCost": 0.1560, "current": true }
  ],
  "totalInputTokens": 12000, "totalOutputTokens": 8000,
  "totalTokens": 20000, "totalEstimatedCost": 0.1560
}
```

### Composants Angular

- `UsageReportService` — `getReport(): Observable<UsageReportView>`.
- `UsageReportView` / `UsagePeriodView` — modèles TS (contrat figé SF-16-01).
- `ReportsComponent` — `/reports` : cartes, table paginée, barres CSS, état vide, erreurs snackbar.
- Lien dans `SettingsComponent` vers `/reports`.

### Endpoint(s) consommé(s)

| Méthode | URL | Auth |
|---------|-----|------|
| GET | `/api/usage/report` | Oui (JWT via `authInterceptor`) |

### Migration Liquibase

- [x] Non applicable (frontend).

---

## Plan de test (`reports.component.spec.ts`, service mocké)

### Tests unitaires

- [ ] Rendu des cartes de synthèse à partir d'un rapport mocké (totaux + période courante).
- [ ] Rendu de la table (nombre de lignes = nombre de périodes).
- [ ] État vide : message dédié quand `periods` est vide.
- [ ] Erreur service → `MatSnackBar` déclenché.
- [ ] `UsageReportService` appelle bien `GET /api/usage/report` (HttpTestingController).

### Isolation

- [x] Non applicable côté frontend (garantie backend via JWT) — le service ne transmet aucun `user_id`.

---

## Dépendances

### Subfeatures bloquantes

- `SF-16-01` (contrat API `GET /api/usage/report`) — contrat **figé** ; tests sur mock, merge après le backend.

### Questions ouvertes impactées

- [ ] Aucune.

---

## Notes et décisions

- **Arbitrage réversible (🟠)** : visualisation en **barres CSS natives** plutôt qu'une lib de graphes
  (Simplicity First, aucune dépendance ajoutée). Réversible : une lib pourra être introduite plus tard.
- **Arbitrage réversible (🟠)** : nouvelle route `/reports` distincte (le tableau de bord de quota
  temps réel F-10 reste ailleurs) ; lien depuis Réglages. Réversible.
- Frontend ne parle qu'à Claude Gateway (`/api/...`) ; isolation garantie backend via JWT.
