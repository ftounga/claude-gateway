# Mini-spec — [F-132 / SF-132-05] Niveau de diagnostic réglable par poste

## Identifiant

`F-132 / SF-132-05`

## Feature parente

`F-132` — Observabilité du runner (journal de diagnostic)

## Statut

`ready`

## Date de création

2026-09-19

## Branche Git

`feat/SF-132-05-niveau-reglable-poste`

---

## Objectif

> En une phrase : permettre de passer un poste en **DEBUG** ponctuellement (le temps d'un diagnostic) via une commande descendante, le runner **revenant automatiquement à INFO** à l'expiration du délai.

---

## Comportement attendu

### Cas nominal

1. Depuis le panneau « Journal du runner » (SF-132-03), l'utilisateur clique **« Activer le DEBUG (10 min) »**.
2. Le frontend appelle `POST /runner-hosts/{hostId}/diag/level` (JWT, poste possédé).
3. Le backend envoie au runner une trame de contrôle `runner_diag_level` (`{level:"DEBUG", ttlSeconds}`) sur le canal descendant existant (`sendControl` local, sinon `broadcastControl` cross-pod — comme la mise à jour du runner). La réponse dit si la commande a été **remise** (`delivered`).
4. Le runner reçoit `runner_diag_level` (aiguillé par `FrameRouter`) et passe `RunnerDiag` en **DEBUG** avec une **échéance** ; à l'expiration (paresseuse : constatée au prochain événement/relevé), il **revient à INFO**. Aucun ordonnanceur dédié — la bascule est portée par le collecteur lui-même.
5. Tant que DEBUG est actif, la trame `runner_diag` porte aussi les événements DEBUG (ex. ticks Vigie), visibles dans le panneau après rafraîchissement.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| `hostId` d'un autre utilisateur / inexistant | Accès refusé, indiscernable | 404 |
| Sans droit runner | Accès refusé | 403 |
| Runner hors ligne (aucun canal) | `200` avec `delivered=false` — l'écran dit « poste non joignable » ; rien n'est cassé | 200 |
| `minutes` hors bornes | Ramené dans `[1, 60]` (défaut 10) | 200 |
| Trame `runner_diag_level` illisible côté runner | Ignorée en silence (compat ascendante), niveau inchangé | — |

---

## Critères d'acceptation

- [ ] `POST /runner-hosts/{hostId}/diag/level` envoie une trame de contrôle `runner_diag_level` au runner et rend `delivered`.
- [ ] **Isolation** : poste d'un autre utilisateur → **404** (test) ; sans droit runner → 403.
- [ ] Runner hors ligne → `200 delivered=false` (aucune exception).
- [ ] Le runner passe en DEBUG à la réception et **revient à INFO** à l'expiration du délai (test de bascule + revert).
- [ ] `minutes` est borné `[1, 60]` (défaut 10).
- [ ] Frontend : un bouton dans le panneau Journal déclenche l'activation et signale le résultat (remis / non joignable) sans casser la Vigie.
- [ ] Une trame `runner_diag_level` illisible/incomplète ne change pas le niveau (best-effort).

---

## Périmètre

### Hors scope (explicite)

- L'émission (SF-132-01), le stockage/exposition (SF-132-02), le panneau de lecture (SF-132-03) — livrés (ici on **ajoute** le bouton d'activation).
- Un réglage **permanent** du niveau (le retour à INFO est **automatique**).
- Le snapshot à la demande (SF-132-04, option).

---

## Valeurs initiales / validation

| Champ | Valeur | Règle |
|-------|--------|-------|
| `minutes` | défaut 10 | borné `[1, 60]` |
| `level` (émis) | `DEBUG` | seul niveau temporaire proposé (le but est le diagnostic ponctuel) |
| retour | `INFO` | niveau de base après expiration (défaut PO) |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle |
|---------|-----|------|------|
| POST | `/api/runner-hosts/{hostId}/diag/level` | Oui (JWT) | accès runner + poste possédé |

Corps : `{ "minutes"?: number }`. Réponse : `{ "delivered": boolean, "level": "DEBUG", "minutes": number }`.

Trame descendante : `{"type":"runner_diag_level","level":"DEBUG","ttlSeconds":<minutes*60>}` (canal existant).

### Tables impactées

Aucune (commande volatile ; aucune persistance).

### Migration Liquibase

- [ ] Non applicable.

### Composants

- **Runner** : `RunnerDiag.setTemporaryLevel(level, ttlSeconds)` + revert paresseux (échéance interne, horloge injectable) ; `FrameRouter` case `runner_diag_level`.
- **Backend** : `RunnerDiagControlService` (build + `sendControl`/`broadcastControl`), `RunnerHostController` `POST /{hostId}/diag/level`, DTOs `RunnerDiagLevelRequest`/`RunnerDiagLevelResponse`.
- **Frontend** : `VigieService.setRunnerDiagDebug(hostId, minutes)`, bouton dans `RunnerDiagJournalComponent`.

---

## Plan de test

### Tests unitaires

- [ ] Runner `RunnerDiag` — `setTemporaryLevel(DEBUG, ttl)` laisse passer le DEBUG ; après l'échéance (horloge avancée), il **revient à INFO** ; `setLevel` permanent annule l'échéance.
- [ ] Runner `FrameRouter` — une trame `runner_diag_level` valide règle le niveau ; illisible/inconnue → sans effet, jamais d'erreur.
- [ ] Backend `RunnerDiagControlService` — `requireOwned` d'abord ; envoie la bonne trame ; `delivered` reflète `sendControl`/`broadcast` ; `minutes` borné.

### Tests d'intégration

- [ ] `POST /api/runner-hosts/{hostId}/diag/level` → 200 (`delivered` selon la présence d'un canal), **404** cross-user, 403 sans droit runner.

### Tests frontend

- [ ] `VigieService.setRunnerDiagDebug` émet le bon `POST`.
- [ ] `RunnerDiagJournalComponent` — le bouton appelle le service et affiche le résultat (remis / non joignable).

### Isolation utilisateur

- [x] Applicable — `requireOwned(userId, hostId)` (404 cross-user), identité du JWT.

---

## Préoccupations transversales

| Préoccupation | Impacté ? | Composants |
|--------------|-----------|-----------|
| **Auth / Principal** | Oui (nouvel endpoint) | `POST /{hostId}/diag/level` gardé par `AtelierAccessService.requireRunnerAccess()` + `CurrentUser.requireId()` — même schéma que les autres endpoints `RunnerHostController`. Aucun changement du Principal ni de la session. |
| **Contexte tenant** | Oui | Nouvel accès : `requireOwned(userId, hostId)` avant tout envoi. Aucune donnée persistée. Composants : `RunnerDiagControlService`, `RunnerHostController#setDiagLevel`. |
| Plans / limites | Non | — |
| **Navigation / routing** | Non (le bouton s'ajoute au panneau existant ; aucune route) | `RunnerDiagJournalComponent` |

---

## Notes et décisions

- **Revert paresseux côté runner** (échéance interne au collecteur, constatée au prochain événement) plutôt qu'un ordonnanceur dédié : le flux de diagnostic est régulier (relevé Vigie ~20 s, drain ~5 s) et le collecteur est déjà le point de passage — plus simple, testable via une horloge injectable, aucun thread de plus.
- **Commande volatile, aucune persistance** : régler un niveau ne survit pas à un redémarrage du runner (il repart à INFO), ce qui est le comportement attendu d'un DEBUG ponctuel.
- **200 `delivered=false`** plutôt qu'une erreur quand le runner est hors ligne : l'écran informe sans traiter cela comme une panne.
