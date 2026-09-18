# Mini-spec — F-128 / SF-128-01 — Rejoindre & capturer depuis la Vigie

## Identifiant

`F-128 / SF-128-01`

## Feature parente

`F-128` — Capturer et exploiter une réunion Teams

## Statut

`ready`

## Date de création

2026-09-18

## Branche Git

`feat/SF-128-01-rejoindre-et-capturer`

---

## Objectif

> Depuis la Vigie, un bouton **« Rejoindre & capturer »** ouvre l'URL de la réunion **dans le Chrome
> managé** (F-122) et **crée un artefact « réunion »** isolé `user_id` + `host_id` (indicateur « capture
> en cours », rappel de consentement, durée de rétention), avec les transitions d'état idle → enregistre →
> pause → terminé.

---

## Comportement attendu

### Cas nominal

1. L'utilisateur est dans la Vigie d'un **poste** (`hostRef`) qu'il possède, activé dans l'espace **VIGIE**,
   avec le **droit Teams** et l'**entitlement VIGIE**.
2. Il clique **« Rejoindre & capturer »**, saisit/colle l'**URL de la réunion**, rattache éventuellement un
   **sujet**, **coche le consentement** (« j'ai prévenu les participants »), et choisit la **rétention**
   (défaut 30 j).
3. `POST /api/vigie/hosts/{hostRef}/meetings` : le backend valide, **crée une ligne `meetings`**
   (`state=RECORDING`, `user_id`, `host_id`, `subject_id?`, `meeting_url`, `started_at`,
   `retention_days`, `consent_acknowledged=true`) puis **ordonne au runner** d'ouvrir l'URL dans le
   **Chrome managé** (§2bis, tool runner `teams_meeting_join` → `Page.navigate` sur l'onglet Teams). La
   réponse est le DTO réunion.
4. La Vigie affiche l'**indicateur « capture en cours »** (pastille rouge pulsée), le sujet rattaché, le
   rappel de consentement et la rétention, avec les boutons **Arrêter** / **Pause**.
5. `POST …/meetings/{id}/stop` → ordonne l'arrêt au runner, passe `state=STOPPED`, renseigne `ended_at`.
   `pause`/`resume` font transiter `RECORDING ↔ PAUSED`.
6. `GET …/meetings` (liste) et `GET …/meetings/{id}` (détail) rendent l'artefact, filtrés
   `user_id` + `host_id`.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| URL de réunion absente / invalide (non http/https, > 2048) | Message explicite | 400 |
| Consentement non coché | « Confirmez que vous avez prévenu les participants. » | 400 |
| Rétention hors bornes [1;365] | Message explicite | 400 |
| Poste inconnu ou d'un autre utilisateur | Accès refusé (indistinct) | 404 |
| Poste non activé dans la Vigie | `host_not_in_vigie` | 409 |
| Pas d'entitlement VIGIE | `space_not_entitled` (pitch d'espace) | 403 |
| Pas de droit Teams | `teams_access_denied` | 403 |
| Runner injoignable | `runner_unavailable` | 409 |
| Chrome managé injoignable / onglet Teams perdu (résultat runner) | `managed_chrome_unreachable`, message guidant vers « Rejoindre & capturer » | 409 |
| Réunion (id) inconnue pour ce poste/utilisateur | Introuvable | 404 |
| Transition d'état invalide (stop d'une réunion déjà STOPPED) | `invalid_state` | 409 |

---

## Critères d'acceptation

- [ ] `POST …/meetings` avec URL valide + consentement crée une ligne `meetings` (`state=RECORDING`) et
      rend 201 avec le DTO, `started_at` renseigné, `retention_days` = valeur (défaut 30).
- [ ] Le backend émet un ordre runner `teams_meeting_join` (ouvre l'URL dans le Chrome managé) ; le runner
      **navigue réellement** l'onglet Teams du Chrome managé vers l'URL.
- [ ] Si le runner rend un échec nommé (chrome injoignable / onglet perdu), la réunion n'est **pas** créée
      en RECORDING silencieux : réponse 409 avec message guidant, aucune ligne fantôme (rollback).
- [ ] `POST …/{id}/stop` passe `STOPPED` + `ended_at` ; `pause`/`resume` font `RECORDING ↔ PAUSED` ;
      une transition invalide rend 409.
- [ ] `GET …/meetings` ne rend que les réunions du couple (`user_id`, `host_id`) courant.
- [ ] **Isolation** : un utilisateur B ne peut ni lire, ni arrêter, ni voir une réunion créée par A
      (404), même en connaissant l'`id` et un `hostRef`.
- [ ] Consentement obligatoire : sans `consent_acknowledged=true`, 400 et aucune capture.
- [ ] Écran Vigie : bouton « Rejoindre & capturer » (dialog URL + sujet + consentement + rétention),
      indicateur « capture en cours » visible tant que `state ∈ {RECORDING, PAUSED}`, boutons Arrêter/Pause.
- [ ] Aucune couleur/police hors `DESIGN_SYSTEM.md` (navy `--cg-primary`, gold `--cg-accent`, peau Teams).

---

## Périmètre

### Hors scope (explicite) — livré par les SF suivantes

- **Enregistrement des octets (vidéo/audio onglet + micro mixé)** → **SF-128-02** (le runner `teams_meeting_join`
  de SF-128-01 **ouvre et navigue** l'onglet ; le média est capturé en 02). **DRAPEAU** : en SF-128-01,
  `state=RECORDING` signifie « session de capture ouverte / onglet rejoint » ; les octets média arrivent en 02.
- **Images clés du partage d'écran** → SF-128-03.
- **Transcription (STT)** → SF-128-04.
- **Exploitation (résumé/décisions/actions/Q&A, multimodal, écran artefact riche)** → SF-128-05.
- **Sélection depuis le calendrier F-89** : v1 = URL saisie/collée (le picker calendrier reste évolution).
- **Rétention active (purge auto, suppression)** → SF-128-07 (ici on **stocke** seulement `retention_days`).

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| state | `RECORDING` | à la création (session ouverte, onglet rejoint) |
| consent_acknowledged | `true` | imposé : la création refuse sans consentement |
| retention_days | `30` | défaut si non fourni ; borné [1;365] |
| started_at | `now()` | horodaté à la création |
| ended_at | `null` | renseigné à `stop` |

Comportements à la création : `created_at` par la base ; `user_id` = utilisateur connecté (JWT) ;
`host_id` = poste résolu depuis `hostRef` et possédé par l'utilisateur.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs | Normalisation |
|-------|-------------|-------------|------------------|---------------|
| meeting_url | Oui | 2048 | http(s):// uniquement | trim |
| title | Non | 300 | texte libre | trim |
| subject_id | Non | — | UUID d'un `radar_subjects` du couple (user_id, host_id) | — |
| consent_acknowledged | Oui | — | doit valoir `true` | — |
| retention_days | Non | — | entier [1;365], défaut 30 | — |
| state | (serveur) | 20 | RECORDING / PAUSED / STOPPED / FAILED | — |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle |
|---------|-----|------|------|
| POST | `/api/vigie/hosts/{hostRef}/meetings` | Oui | propriétaire du poste, VIGIE |
| POST | `/api/vigie/hosts/{hostRef}/meetings/{id}/stop` | Oui | idem |
| POST | `/api/vigie/hosts/{hostRef}/meetings/{id}/pause` | Oui | idem |
| POST | `/api/vigie/hosts/{hostRef}/meetings/{id}/resume` | Oui | idem |
| GET | `/api/vigie/hosts/{hostRef}/meetings` | Oui | idem |
| GET | `/api/vigie/hosts/{hostRef}/meetings/{id}` | Oui | idem |

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `meetings` | CREATE (migration 111) + INSERT/SELECT/UPDATE | isolation `user_id` + `host_id` ; FK `radar_subjects` (subject_id, nullable) |

### Migration Liquibase

- [x] Oui — `111-meetings.xml` (prochain numéro libre).

### Runner (module `runner`)

- Nouveau tool **`teams_meeting_join`** dans `TeamsTools` : ouvre/navigue l'onglet Teams du **Chrome managé**
  vers l'URL de la réunion (`Page.navigate` via l'attache CDP existante — F-122). Rend
  `{joined, tabUrl}` ou un échec nommé (`chrome_unreachable`, `not_teams`). **Aucune capture d'octets ici**
  (SF-128-02). Relayé par `RunnerToolGateway.teamsRead` (préfixe `teams_`), appelé **directement** par le
  service backend (hors boucle agent).

### Composants Angular

- `TeamsMeetingService` (core/services) — appels `/api/vigie/hosts/{hostRef}/meetings*`.
- `JoinAndCaptureDialogComponent` — URL + sujet + consentement + rétention (`mat-form-field outline`, `mat-error`).
- `MeetingCapturePanelComponent` — indicateur « capture en cours » + timer + chips + boutons Arrêter/Pause,
  intégré à l'écran Vigie du poste.

---

## Plan de test

### Tests unitaires (service)

- [ ] Création nominale : ligne créée, state RECORDING, retention défaut 30, ordre runner émis.
- [ ] Refus sans consentement (400), URL invalide (400), rétention hors bornes (400).
- [ ] Échec runner nommé → 409 + rollback (aucune ligne persistée).
- [ ] Transitions stop/pause/resume + transition invalide (409).
- [ ] Isolation : `requireOwned` + filtre `host_id` sur get/list/stop.

### Tests d'intégration (endpoints)

- [ ] `POST …/meetings` → 201 payload valide (runner mocké OK).
- [ ] `POST …/meetings` → 400 sans consentement / URL invalide.
- [ ] `GET …/meetings/{id}` → 404 pour un autre utilisateur (isolation).
- [ ] `POST …/{id}/stop` → 200 puis 409 si déjà STOPPED.
- [ ] `POST …/meetings` → 403 sans droit Teams / entitlement VIGIE, 409 poste hors Vigie.

### Isolation utilisateur

- [x] Applicable — user A crée une réunion, user B (autre poste/compte) reçoit 404 en lecture/arrêt.

### Frontend

- [ ] `TeamsMeetingService` testé avec `provideHttpClientTesting` (URLs, méthodes, mapping).
- [ ] `JoinAndCaptureDialogComponent` : `should create`, validation URL, consentement requis.
- [ ] `MeetingCapturePanelComponent` : `should create`, indicateur visible si RECORDING, clic Arrêter.

---

## Dépendances

### Subfeatures bloquantes

- Aucune. S'appuie sur F-122 (Chrome managé), F-89 (Vigie), l'isolation `RadarScopeResolver`.

### Questions ouvertes impactées

- Aucune de `docs/OPEN_QUESTIONS.md`. Contraintes structurantes (rétention défaut/bornes, états, format URL)
  tranchées ici par défaut raisonnable + drapeau PR.

---

## Préoccupations transversales — composants impactés

- **Auth / Principal** : aucun nouveau type d'auth ; réutilise la résolution `user_id` (JWT) des controllers
  Vigie/Radar existants. Endpoints ajoutés : les 6 ci-dessus, tous protégés (propriétaire du poste).
- **Contexte tenant** : résolution `hostRef → host_id` via le même chemin que `RadarSubjectPageController` ;
  filtre `user_id` + `host_id` (RadarScope) sur tout accès `meetings`. Aucun nouveau moyen de résoudre le tenant.
- **Plans / limites** : gate entitlement `EntitlementSpace.VIGIE` + droit Teams (mêmes services que le volet
  Teams). Pas de nouveau quota en SF-128-01 (le coût STT/tokens arrive en 04/05).
- **Navigation / routing** : le panneau capture s'intègre à l'écran Vigie existant du poste ; **aucune
  nouvelle route** en SF-128-01 (l'écran artefact riche = SF-128-05, route `vigie/:hostRef/reunions/:id`).

---

## Notes et décisions

- **§2bis (Option A)** : capture par **onglet** du Chrome managé — la réunion doit se dérouler dans ce
  Chrome. Le bouton « Rejoindre & capturer » ouvre l'URL dans le Chrome managé (join réel via `Page.navigate`)
  puis, à partir de SF-128-02, capture l'onglet + micro. Message clair si le Chrome managé est injoignable.
- **Gateway-First / Provider-First** : le backend orchestre (artefact, ordre runner, isolation) ; il ne
  capture ni ne transcrit lui-même. Aucun couplage IA ici.
- **DRAPEAU** : en SF-128-01 le média n'est pas encore enregistré (SF-128-02) ; `RECORDING` = session ouverte.
