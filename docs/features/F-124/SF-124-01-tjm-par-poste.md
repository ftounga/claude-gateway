# Mini-spec — F-124 / SF-124-01 — TJM par poste

## Identifiant

`F-124 / SF-124-01`

## Feature parente

`F-124` — Suivi d'activité et de revenu par client (TJM, cumul, CRA)

## Statut

`ready`

## Date de création

2026-09-16

## Branche Git

`feat/SF-124-01-tjm-par-poste`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Permettre à l'utilisateur de fixer un **TJM (€ HT/jour) par poste** et un **mois de départ du cumul**
(par utilisateur, défaut `2025-09`), isolés par `user_id` (+ `host_id`), et d'afficher le TJM à gauche
de chaque poste dans la Forge.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur ouvre un poste dans la Forge, onglet **Activité**, saisit un TJM (ex. `550`) et
   enregistre → `PUT /activity/rates/{hostId}` persiste le TJM (en centimes) pour `(user_id, host_id)`.
2. Le TJM apparaît **à gauche de chaque poste** dans la colonne de la Forge (rail), en JetBrains Mono.
3. L'utilisateur règle le **mois de départ** du cumul (ex. `2025-09`) → `PUT /activity/settings`
   persiste `start_month` pour `user_id`. Sans réglage, le défaut est `2025-09`.
4. `GET /activity/rates` rend les TJM des postes possédés ; `GET /activity/settings` rend le mois de
   départ (ou le défaut).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| TJM négatif ou > 1 000 000 € | Refus, message explicite | 400 |
| TJM absent / non numérique | Refus | 400 |
| `hostId` d'un poste d'un autre utilisateur (ou inexistant) | Poste inexistant, rien écrit | 404 |
| `start_month` hors format `YYYY-MM` (ou mois 00/13) | Refus | 400 |
| Non authentifié | Refus | 401 |

---

## Critères d'acceptation

- [ ] `PUT /activity/rates/{hostId}` avec un TJM valide crée/met à jour la ligne `poste_billing` et rend le TJM enregistré.
- [ ] Un TJM négatif, non numérique ou hors bornes est refusé (400) et rien n'est écrit.
- [ ] `GET /activity/rates` ne rend que les TJM des postes **du user courant** ; jamais ceux d'un autre.
- [ ] `PUT /activity/rates/{hostId}` sur un poste d'un autre user (ou inexistant) renvoie 404 et n'écrit rien.
- [ ] `GET /activity/settings` rend `2025-09` quand aucun réglage n'existe, et la valeur enregistrée sinon.
- [ ] `PUT /activity/settings` refuse un `start_month` mal formé (400) et accepte `YYYY-MM` valide.
- [ ] Le TJM s'affiche à gauche de chaque poste dans la Forge (charte, aucune couleur nouvelle).
- [ ] Isolation `user_id` (+ `host_id`) vérifiée par test : user A ne voit ni ne modifie le TJM de user B.

---

## Périmètre

### Hors scope (explicite)

- Le **calcul du cumul** (jours × TJM, jours ouvrés/fériés, déclaré/supposé) → **SF-124-02**.
- Le **CRA par message** (extraction IA) → **SF-124-03**.
- Toute facturation (factures, TVA, export comptable), multi-devise, TJM par projet.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `poste_billing.daily_rate_cents` | — | créé à la première écriture ; jamais négatif |
| `activity_settings.start_month` | `2025-09` (défaut applicatif si ligne absente) | format `YYYY-MM` |
| `created_at` / `updated_at` | horodatés | gérés par l'entité |
| `user_id` | user courant | jamais un paramètre |

---

## Contraintes de validation

| Champ | Obligatoire | Format / Valeurs autorisées | Normalisation |
|-------|-------------|-----------------------------|---------------|
| `dailyRateCents` | Oui | entier `>= 0` et `<= 100_000_000` (0 à 1 000 000 € HT) | — |
| `startMonth` | Oui | `^\d{4}-(0[1-9]\|1[0-2])$`, année 2000–2100 | trim |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Droit |
|---------|-----|------|-------|
| GET | `/activity/settings` | JWT | Forge (`requireRunnerAccess`) |
| PUT | `/activity/settings` | JWT | Forge |
| GET | `/activity/rates` | JWT | Forge |
| PUT | `/activity/rates/{hostId}` | JWT | Forge + `requireOwned` |
| DELETE | `/activity/rates/{hostId}` | JWT | Forge + `requireOwned` |

(Contexte servlet `/api` → URLs effectives `/api/activity/...`.)

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `poste_billing` (neuve) | INSERT / SELECT / UPDATE / (DELETE) | `(user_id, host_id)` unique ; TJM en centimes |
| `activity_settings` (neuve) | INSERT / SELECT / UPDATE | 1 ligne par `user_id` ; mois de départ |

FK `ON DELETE CASCADE` vers `users(id)` et `runner_hosts(id)` : aucune ligne ne survit au compte ni
au poste (même choix que `host_mail_addresses`, migration `100`).

### Migration Liquibase

- [x] Oui — `109-poste-billing-et-mois-de-depart.xml`

### Composants Angular

- `core/services/poste-billing.service.ts` (neuf) — appels `/api/activity/*`.
- `postes/postes.component.*` — onglet Activité : champ TJM (par poste) + champ mois de départ (global).
- `postes/forge-rail/forge-rail.component.*` — affichage du TJM à gauche de chaque poste (nouvel input
  `billing`, présentationnel, absent en Vigie).
- `core/models/atelier.models.ts` — types `PosteRate`, `ActivitySettings`.

---

## Plan de test

### Tests unitaires (backend)

- [ ] `ActivityBillingService` — set/get TJM (upsert), bornes refusées, isolation `requireOwned`.
- [ ] `ActivityBillingService` — get/set mois de départ, défaut `2025-09`, format refusé.

### Tests d'intégration (backend)

- [ ] `PUT /activity/rates/{hostId}` → 200 avec TJM valide ; 400 hors bornes ; 404 poste d'autrui.
- [ ] `GET /activity/rates` → ne rend que les TJM du user courant.
- [ ] `GET/PUT /activity/settings` → défaut `2025-09`, 400 format invalide, 200 valide.

### Isolation user_id

- [x] Applicable — user A ne lit ni n'écrit le TJM/mois de départ de user B (404 / listes disjointes).

### Tests frontend

- [ ] `poste-billing.service.spec.ts` — requêtes émises, réponses mappées, aucun user id envoyé.
- [ ] `postes.component.spec.ts` / `forge-rail.component.spec.ts` — TJM affiché, champ de config présent.

---

## Préoccupations transversales — analyse d'impact

- **Auth / Principal** : aucun nouveau type d'auth. Endpoints sous la chaîne JWT existante, identité via
  `CurrentUser.requireId()`. Composants impactés : `ActivityBillingController` (nouveau, même patron que
  `RunnerHostController`). Pas de régression sur les endpoints existants.
- **Contexte tenant** : nouvelle donnée par `(user_id, host_id)` et par `user_id`. Résolution du tenant
  inchangée (`CurrentUser`). Isolation par `requireOwned` (postes) et filtre `user_id` (settings).
  Composants : `ActivityBillingService`, repositories `PosteBillingRepository`, `ActivitySettingsRepository`.
- **Plans / limites** : aucun quota consommé (aucun appel fournisseur dans cette SF).
- **Navigation / routing** : **aucune route ni guard ajoutés** — on enrichit l'onglet Activité existant
  et la colonne de la Forge. `app-forge-rail` est partagé avec la Vigie : le nouvel input `billing` est
  optionnel et **non fourni** par la Vigie, donc rien n'y change.

---

## Notes et décisions

- **TJM en centimes** (`daily_rate_cents`, `bigint`) : jamais de flottant en base ; le calcul du cumul
  (SF-124-02) arrondit `jours × TJM` au centime.
- **Deux tables neuves** dans une migration (config d'activité cohérente) : `poste_billing` (TJM par
  poste) et `activity_settings` (mois de départ par user). Noms retenus d'après le cadrage §7 (indicatif).
- **Purge** : garantie par FK `ON DELETE CASCADE` (comme `host_mail_addresses`) — aucune purge applicative
  à écrire (suppression de poste et de compte couvertes).
