# Mini-spec — [F-16 / SF-16-01] Rapport d'usage & coût (backend)

## Identifiant

`F-16 / SF-16-01`

## Feature parente

`F-16` — Rapports d'usage & coût in-app

## Statut

`done`

## Date de création

2026-07-02

## Branche Git

`feat/SF-16-01-usage-report-backend`

---

## Objectif

> Exposer `GET /api/usage/report` : l'historique mensuel de consommation de tokens de l'utilisateur
> courant, avec une estimation de coût par période et des totaux, isolé par `user_id`.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur authentifié (JWT) appelle `GET /api/usage/report`.
2. Le service lit **ses** compteurs d'usage (`usage_counters`, F-10) filtrés sur `user_id`,
   triés par période décroissante, limités aux `max-months` derniers mois (défaut 12).
3. Pour chaque période : tokens d'entrée, de sortie, total, et **coût estimé** =
   `input/1e6 × prix_entrée_par_million + output/1e6 × prix_sortie_par_million` (BigDecimal, scale 4,
   HALF_UP). La période correspondant au mois calendaire UTC courant est marquée `current: true`.
4. Le service calcule les totaux sur la fenêtre retournée (input, output, total, coût).
5. Réponse `200 OK` avec le rapport. Aucune donnée sensible (ni Stripe, ni clé).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Requête non authentifiée (JWT absent/invalide) | Rejet, aucun corps métier | 401 |
| Utilisateur sans aucun compteur | `periods: []` + totaux à 0, `currency` renseignée | 200 |
| Compteurs d'un autre utilisateur | Jamais retournés (filtre `user_id`) | 200 (les siens seulement) |

---

## Critères d'acceptation

- [ ] `GET /api/usage/report` renvoie 200 avec `currency`, `periods[]` et les 4 totaux.
- [ ] Chaque `period` porte `periodStart`, `periodEnd`, `inputTokens`, `outputTokens`,
      `totalTokens`, `estimatedCost`, `current`.
- [ ] Le coût estimé est calculé depuis la configuration (`app.usage.report.*`), jamais en dur.
- [ ] Les périodes sont triées de la plus récente à la plus ancienne et limitées à `max-months`.
- [ ] Un nouvel utilisateur (aucun compteur) reçoit `periods: []` et des totaux à 0 (pas d'erreur).
- [ ] Sécurité : un utilisateur ne voit que **ses** périodes (isolation `user_id`), `user_id` pris
      du `CurrentUser` (JWT), jamais d'un paramètre client.
- [ ] `GET /api/usage/report` sans JWT → 401.

---

## Périmètre

### Hors scope (explicite)

- Aucune ventilation par modèle Claude (les compteurs F-10 agrègent input/output sans modèle) :
  le coût est une **estimation** au tarif configuré, pas un coût facturé réel.
- Aucune facturation ni prélèvement (OQ-08 : overage non monétisé en V1).
- Pas de graphes/rendu (SF-16-02, frontend).
- Pas d'agrégation cross-utilisateur / vue admin (relèverait d'une feature admin).

---

## Valeurs initiales

Aucune entité créée. Lecture seule sur `usage_counters` (F-10).

---

## Contraintes de validation

| Champ | Obligatoire | Règle |
|-------|-------------|-------|
| (aucun paramètre d'entrée) | — | Identité issue du JWT uniquement |

Config (`app.usage.report`, externalisée, réversible, non secrète) :

| Clé | Défaut | Rôle |
|-----|--------|------|
| `currency` | `EUR` | Devise affichée |
| `max-months` | `12` | Nombre max de périodes retournées |
| `input-cost-per-million-tokens` | `3.00` | Prix estimé par million de tokens d'entrée |
| `output-cost-per-million-tokens` | `15.00` | Prix estimé par million de tokens de sortie |

---

## Technique

### Contrat API (FIGÉ — importé tel quel par SF-16-02)

```
GET /api/usage/report
Auth : JWT obligatoire (Bearer)
Params : aucun

200 OK
{
  "currency": "EUR",
  "periods": [
    {
      "periodStart": "2026-07-01",   // ISO YYYY-MM-DD, 1er jour du mois (UTC)
      "periodEnd":   "2026-08-01",   // borne exclusive (mois suivant)
      "inputTokens":  12000,
      "outputTokens":  8000,
      "totalTokens":  20000,
      "estimatedCost": 0.1560,       // number, devise = currency, scale 4
      "current": true                // true pour le mois calendaire UTC courant
    }
  ],
  "totalInputTokens":  12000,
  "totalOutputTokens":  8000,
  "totalTokens":       20000,
  "totalEstimatedCost": 0.1560
}

401 Unauthorized  // JWT absent/invalide (aucun corps métier)
```

Notes de contrat :
- `periods` est trié **décroissant** (plus récent d'abord), au plus `max-months` éléments.
- Liste vide possible (nouvel utilisateur) : `periods: []`, tous les totaux à 0.
- `estimatedCost` / `totalEstimatedCost` : nombres (BigDecimal scale 4) ; le formatage devise est
  à la charge du frontend.

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/usage/report` | Oui | USER |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `usage_counters` (F-10) | SELECT (filtre `user_id`) | Lecture seule, aucune modification de schéma |

### Migration Liquibase

- [x] Non applicable — réutilise `usage_counters` (migration `009`), aucune nouvelle table/colonne.

### Composants backend

- `UsageReportProperties` (`@ConfigurationProperties("app.usage.report")`) — devise, fenêtre, tarifs.
- `UsageReportService` — construit le rapport (isolation `user_id`, tri, limite, coût, totaux).
- `UsageReport` / `UsagePeriod` — objets métier (records).
- `UsageReportResponse` / `UsagePeriodResponse` — DTOs de réponse (records, projection `from`).
- `UsageController#report()` — endpoint fin (`GET /usage/report`).
- `UsageCounterRepository#findByUserIdOrderByPeriodStartDesc` — requête dérivée (filtre `user_id`).

---

## Plan de test

### Tests unitaires (`UsageReportServiceTest`, horloge figée)

- [ ] Rapport vide si aucun compteur (periods vide, totaux à 0, devise présente).
- [ ] Une période : tokens et coût estimé corrects (tarif configuré), marquée `current` si mois courant.
- [ ] Plusieurs périodes triées décroissant et limitées à `max-months`.
- [ ] Totaux = somme des périodes retournées.
- [ ] Coût estimé calculé au tarif configuré (input + output), scale 4.

### Tests d'intégration (`UsageReportApiIntegrationTest`, MockMvc)

- [ ] `GET /api/usage/report` → 200, structure conforme au contrat pour un utilisateur avec compteurs.
- [ ] `GET /api/usage/report` → 200 `periods: []` pour un nouvel utilisateur.
- [ ] `GET /api/usage/report` sans JWT → 401.

### Isolation user_id

- [x] Applicable — test : l'utilisateur A ne voit jamais les périodes de l'utilisateur B.

---

## Dépendances

### Subfeatures bloquantes

- `SF-10-01` (usage_counters + enregistrement consommation) — **done**.

### Questions ouvertes impactées

- [ ] OQ-08 (overage) — inchangée : F-16 est un **rapport**, pas une facturation.

---

## Notes et décisions

- **Arbitrage réversible (🟠)** : le coût est une **estimation** au tarif blended configuré
  (input/output par million), faute de ventilation par modèle dans `usage_counters`. Réversible :
  un enrichissement futur (tokens par modèle) permettrait un coût par modèle sans casser ce contrat.
- **Arbitrage réversible (🟠)** : seules les périodes réellement enregistrées sont retournées ; une
  période courante sans consommation n'apparaît pas (le frontend gère l'absence). Réversible.
- Gateway-First : lecture/agrégation relationnelle pure, aucun appel IA, aucun traitement lourd.
