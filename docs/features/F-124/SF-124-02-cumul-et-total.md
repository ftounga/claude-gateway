# Mini-spec — F-124 / SF-124-02 — Cumul & total

## Identifiant

`F-124 / SF-124-02`

## Feature parente

`F-124` — Suivi d'activité et de revenu par client (TJM, cumul, CRA)

## Statut

`ready`

## Date de création

2026-09-16

## Branche Git

`feat/SF-124-02-cumul-et-total`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Calculer et afficher le **cumul de revenu par poste** (jours × TJM depuis le mois de départ) et le
**total tous clients**, en distinguant la part **déclarée** (CRA) de la part **supposée** (mois
complet auto), isolé par `user_id`.

---

## Comportement attendu

### Cas nominal

Pour chaque mois du **mois de départ** jusqu'au **mois courant**, et pour chaque poste ayant un TJM :
- si un **CRA est déclaré** pour (poste, mois) → jours = jours déclarés (**source = déclaré**) ;
- sinon si le mois est **antérieur** au mois courant → jours = **jours ouvrés du mois** (lun–ven hors
  fériés France, calculés : fixes + Pâques) (**source = supposé**) ;
- sinon (mois **courant**, non déclaré) → **0 jour** (le mois courant n'est pas supposé tant qu'il
  n'est pas déclaré — défaut prudent, décision cadrage §3).
- revenu(poste, mois) = arrondi(jours × TJM) ; **cumul(poste)** = Σ mois ; **total** = Σ postes.

`GET /activity/revenue` rend, par poste : `tjmCents`, `cumulCents`, `declaredCents`, `supposedCents`,
et les totaux `totalCents` / `totalDeclaredCents` / `totalSupposedCents`, plus `startMonth` et
`currentMonth`. La Forge affiche le cumul à gauche de chaque poste (« dont X € supposés ») et le
**total bien en avant en haut de la Forge**.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Non authentifié | Refus | 401 |
| Sans droit Forge | Refus | 403 |
| Poste sans TJM | Absent du calcul (ni cumul, ni supposé) | 200 |
| Aucun poste / aucun TJM | Totaux à 0, liste vide | 200 |

---

## Critères d'acceptation

- [ ] Jours ouvrés d'un mois = lun–ven **hors fériés France** (fixes + Pâques/Ascension/Pentecôte), vérifié par test (ex. mai avec 1er/8 mai et Ascension).
- [ ] Un mois **déclaré** compte les jours déclarés (part **déclarée**), demi-journées (0,5) admises.
- [ ] Un mois passé **non déclaré** compte un mois complet (part **supposée**).
- [ ] Le mois **courant non déclaré** compte **0** (ne gonfle pas le total).
- [ ] Le mois **courant déclaré** compte les jours déclarés.
- [ ] cumul(poste) = déclaré + supposé ; total = Σ postes ; montants en centimes, arrondis au centime.
- [ ] La Forge affiche le cumul par poste (part supposée visible) et le **total en haut**.
- [ ] Isolation `user_id` : le cumul d'un user n'inclut jamais les postes/CRA d'un autre.

---

## Périmètre

### Hors scope (explicite)

- L'**écriture** des CRA par message NL (extraction IA) → **SF-124-03**. Cette SF crée la table
  `cra_entries` et la **lit** ; elle ne l'écrit pas via l'IA (les tests insèrent des lignes en direct).
- Facturation, TVA, multi-devise, prévisionnel.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `cra_entries.days` | — | numeric(4,1), 0 < days ≤ jours ouvrés du mois (validation d'écriture en SF-124-03) |
| mois de départ | `2025-09` (SF-124-01) | borne basse du cumul |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Droit |
|---------|-----|------|-------|
| GET | `/activity/revenue` | JWT | Forge (`requireRunnerAccess`) |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `cra_entries` (neuve) | SELECT (lecture) ; créée ici | `(user_id, host_id, year_month)` unique ; `days numeric(4,1)` |
| `poste_billing` (SF-124-01) | SELECT | TJM par poste |
| `activity_settings` (SF-124-01) | SELECT | mois de départ |

### Migration Liquibase

- [x] Oui — `110-cra-entries.xml`

### Composants Angular

- `core/services/poste-billing.service.ts` — `revenue()` (`GET /api/activity/revenue`).
- `postes/postes.component.*` — cumul par poste (rail) + total en haut de la Forge (bandeau).
- `postes/forge-rail/forge-rail.component.*` — cumul + part supposée par poste (nouvel input `revenue`).
- `shared/money.ts` — réutilisé.

---

## Plan de test

### Tests unitaires (backend)

- [ ] `FrenchHolidays` — fériés fixes + mobiles (Pâques 2025/2026, Ascension, lundi de Pentecôte).
- [ ] `WorkdayCalendar` — jours ouvrés d'un mois (mois avec fériés ; week-ends exclus).
- [ ] `RevenueService` — mois complet (supposé), mois déclaré, demi-journées, mois courant non supposé, cumul multi-mois, total multi-postes, poste sans TJM ignoré.

### Tests d'intégration (backend)

- [ ] `GET /activity/revenue` → totaux et parts déclaré/supposé corrects (avec CRA insérés).
- [ ] Isolation : un user ne voit jamais le cumul d'un autre (postes + CRA disjoints).

### Isolation user_id

- [x] Applicable — filtre `user_id` sur TJM, CRA et settings ; testé.

### Tests frontend

- [ ] `poste-billing.service.spec.ts` — `revenue()` émis et mappé.
- [ ] `forge-rail`/`postes` — cumul par poste + « dont X € supposés » + total en haut.

---

## Préoccupations transversales — analyse d'impact

- **Auth / Principal** : nouvel endpoint sous la chaîne JWT existante, identité via `CurrentUser`.
  Composant : `RevenueController` (nouveau). Aucun impact sur les endpoints existants.
- **Contexte tenant** : lecture agrégée par `user_id` uniquement (TJM, CRA, settings). Composants :
  `RevenueService`, `CraEntryRepository`, `PosteBillingRepository`, `ActivitySettingsRepository`.
- **Plans / limites** : aucun quota consommé (calcul local, aucun appel fournisseur).
- **Navigation / routing** : aucune route/guard ajoutés — enrichissement du bandeau et de la colonne
  de la Forge. `app-forge-rail` reste partagé : le nouvel input `revenue` est **optionnel**, la Vigie
  ne le fournit pas.

---

## Notes et décisions

- **`cra_entries` créée ici** (et non en SF-124-03) : c'est l'**entrée** du calcul. SF-124-02 la lit ;
  SF-124-03 ajoute le chemin d'écriture (extraction IA). Les tests de SF-124-02 insèrent des lignes en
  direct pour éprouver la part déclarée.
- **Mois courant non supposé** (décision cadrage §3) : un mois inachevé ne gonfle pas le total.
- **Arrondi au centime** de `jours × TJM` (les demi-journées sur un TJM impair en centimes).
- Fériés France **calculés** (Meeus/Butcher pour Pâques), aucun service externe.
