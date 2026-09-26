# Mini-spec — F-117 / SF-117-05 : le nouveau départ manuel pose un marqueur de repli distinct de la compaction

## Identifiant

`F-117 / SF-117-05`

## Feature parente

`F-117` — Le contexte d'un terminal ne déborde jamais

## Statut

`ready`

## Date de création

2026-09-26

## Branche Git

`feat/SF-117-05-marqueur-repli-nouveau-depart`

---

## Objectif

Persister et exposer un marqueur serveur qui distingue « l'utilisateur a demandé un nouveau départ »
(à replier) de « compaction automatique » (à garder affichée), pour que le repli d'affichage tienne
au rechargement.

---

## Comportement attendu

### Cas nominal

1. `POST /api/workspaces/{id}/chat/restart` (nouveau départ manuel) pose, en plus de la frontière
   `chat_thread_started_at` et de l'effacement du résumé (comportement SF-39-04/SF-117-01 inchangé),
   la colonne **`chat_history_folded_at = now()`**.
2. `AtelierCompactionService` (compaction automatique) **ne touche pas** `chat_history_folded_at` :
   il continue de n'écrire que `chat_thread_started_at` + `chat_thread_summary`.
3. `GET /api/workspaces/{id}/chat/resume` et la réponse de `restart` renvoient un entier
   **`foldedTurns`** = nombre de messages (du user, isolés `user_id`) créés **avant**
   `chat_history_folded_at`. `chat_history_folded_at == null` ⇒ `foldedTurns = 0`.
4. Comportement identique au rechargement : `foldedTurns` est recalculé depuis la donnée persistée.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Workspace inexistant ou appartenant à un autre utilisateur | Introuvable, rien lu ni écrit (`requireOwned`) | 404 |
| Aucun nouveau départ jamais fait (`chat_history_folded_at` null) | `foldedTurns = 0`, affichage normal | 200 |
| Projet existant d'avant la migration | Colonne null ⇒ `foldedTurns = 0`, comportement d'avant | 200 |

---

## Critères d'acceptation

- [ ] Un nouveau départ manuel écrit `chat_history_folded_at = now()` (non null après `restart`).
- [ ] La compaction automatique **ne modifie pas** `chat_history_folded_at` (test dédié).
- [ ] `resume` renvoie `foldedTurns` = nombre de messages créés avant `chat_history_folded_at`.
- [ ] `chat_history_folded_at == null` ⇒ `foldedTurns == 0` (resume et restart).
- [ ] Une compaction survenant **après** un nouveau départ déplace `chat_thread_started_at` mais
      laisse `chat_history_folded_at` (et donc `foldedTurns`) **inchangé**.
- [ ] Isolation : un projet non possédé n'est ni lu ni écrit (404), le comptage filtre `user_id`.
- [ ] La migration 122 joue sur base propre (H2 + PostgreSQL via contexte Spring), schéma validé.

---

## Périmètre

### Hors scope (explicite)

- Tout le rendu / affordance « Voir l'historique » → **SF-117-06** (frontend).
- Changement du calcul de rejeu (`turns`, `chat_thread_started_at`) : inchangé.
- Suppression de messages : rien n'est jamais supprimé.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `chat_history_folded_at` | `null` | Posé à `now()` uniquement par `restart` ; jamais par la compaction |

Comportements : projets existants → colonne null (nullable, pas de backfill) = comportement d'avant.

---

## Contraintes de validation

| Champ | Obligatoire | Format | Notes |
|-------|-------------|--------|-------|
| `chat_history_folded_at` | Non | `timestamptz` nullable | Écrit serveur uniquement, jamais reçu du client |
| `foldedTurns` (réponse) | — | entier ≥ 0 | Dérivé ; jamais persisté tel quel |

---

## Technique

### Endpoint(s)

| Méthode | URL | Auth | Rôle |
|---------|-----|------|------|
| GET | `/api/workspaces/{id}/chat/resume` | Oui | propriétaire | (réponse enrichie de `foldedTurns`) |
| POST | `/api/workspaces/{id}/chat/restart` | Oui | propriétaire | (pose le marqueur, réponse enrichie) |

Contrats **rétro-compatibles** : ajout d'un champ à `AtelierResumeResponse`, aucune suppression.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| `workspaces` | ALTER (ADD COLUMN `chat_history_folded_at`) + UPDATE (restart) + SELECT count | nullable |
| `atelier_messages` | SELECT count (isolé `workspace_id` + `user_id`) | comptage `< chat_history_folded_at` |

### Migration Liquibase

- [x] Oui — `122-workspaces-chat-history-folded-at.xml`
- [ ] Non applicable

### Composants Angular

- Aucun (backend seul ; l'UI est SF-117-06).

---

## Plan de test

### Tests unitaires (`AtelierThreadServiceTest`)

- [ ] `restart` pose `chat_history_folded_at` non null et le renvoie via `foldedTurns`.
- [ ] `resumeState` compte les messages avant `chat_history_folded_at` (`foldedTurns` correct).
- [ ] `chat_history_folded_at == null` ⇒ `foldedTurns == 0` (resume + restart).
- [ ] Isolation : projet non possédé ⇒ exception, rien écrit.

### Tests unitaires (`AtelierCompactionServiceTest`)

- [ ] La compaction n'écrit pas `chat_history_folded_at` (reste à sa valeur d'entrée, y compris
      non null après un nouveau départ antérieur).

### Tests d'intégration / contexte

- [ ] Migration 122 jouée au démarrage du contexte Spring (schéma validé, H2 + PostgreSQL).

### Isolation workspace

- [x] Applicable — le comptage filtre `workspace_id` + `user_id` ; `requireOwned` garde l'accès.

---

## Dépendances

### Subfeatures bloquantes

- `SF-39-04` (restart/resume) — done.
- `SF-117-01` (compaction) — done.

### Subfeature aval (planifiée)

- `SF-117-06` (frontend) — consomme `foldedTurns`.

### Questions ouvertes impactées

- Aucune (OQ-01/02/03/10 sans objet ici).

---

## Notes et décisions

- **Colonne dédiée** plutôt que réutilisation de `chat_thread_summary == null` : après un nouveau
  départ, une compaction ultérieure repose un résumé et déplace la frontière — le signal serait
  perdu. Voir cadrage §3.
- `foldedTurns` (comptage) plutôt qu'un timestamp exposé : évite au frontend toute comparaison de
  dates ; replier = masquer les N premiers messages du fil chargé en ordre croissant.
