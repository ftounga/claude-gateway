# Mini-spec — F-245 / SF-245-01 Le fil Jarvis existe

## Identifiant
`F-245 / SF-245-01`

## Feature parente
`F-245` — Le fil central de Jarvis (cadrage `CADRAGE-F-245-le-fil-central-de-jarvis.md`, D1, D9)

## Statut
`done` (2026-11-03) · **Estimation :** 0,75 j · **Branche :** `feat/SF-245-01-le-fil-jarvis-existe` · **Dépend de :** —

## Objectif
Chaque utilisateur a un fil Jarvis unique, créé à la demande, visible en tête de l'Atelier, qui n'est ni un sujet ni un projet.

## Comportement attendu

### Cas nominal
1. **Le lieu** : une ligne `workspaces` portant `jarvis_thread_user_id = user_id` (migration `245-workspaces-jarvis-thread.xml`, contrainte `UNIQUE`). Nom « Jarvis », sans poste (`host_id` nul, client « Hébergé »), source `ARCHIVE`, cible `SANDBOX`. Il hérite du rejeu, de la compaction, du cache et de l'écran du terminal.
2. **`GET /api/jarvis/thread`** (`JarvisThreadController`, garde Forge `requireAccess`) : crée le fil s'il manque et rend `{ id, name }`. Idempotent. La création se fait dans sa **propre transaction** (`REQUIRES_NEW`) : un appel concurrent qui perd la course sur la contrainte relit le fil du gagnant.
3. **En tête de l'Atelier** : `GET /api/workspaces` le place en premier (tri stable) et expose `jarvisThread: true`. L'écran l'affiche avec l'icône `record_voice_over`, sans menu « Supprimer le projet ». S'il est absent de la liste, l'Atelier appelle `GET /api/jarvis/thread` puis relit la liste (best-effort).
4. **Exclusions** (« ce n'est pas un sujet ») : univers de Jarvis (`JarvisUniverseService`), activité de Jarvis (`JarvisActivityService`), projets sans poste « Hébergé » (`WorkspaceService.listWithoutHost` → accueil de la Forge, portée de gouvernance), tours du poste « Hébergé » (`PosteTurnsService`, sauf depuis le fil lui-même), routines (`RoutineService.isRoutineCapable`), sélecteur de sujet de `/jarvis`.
5. **Fixe** : renommer, supprimer depuis l'écran, rattacher à un poste ou changer la cible → **400** (`invalid_archive`, « Le fil Jarvis ne se renomme, ne se déplace ni ne se supprime. »).
6. **Purge du compte** : `AccountService` supprime le fil comme les autres espaces (la garde ne vise que l'écran).
7. **Consigne système** : celle d'un sujet hébergé, **à l'octet près** ; la panoplie d'outils aussi (aucune branche ne lit `isJarvisThread()` dans `buildPrompt`/`buildTools`).

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Deux créations concurrentes | une seule ligne ; les deux rendent le même id | 200 |
| `DELETE /api/workspaces/{fil}` | refusé, le fil reste | 400 |
| `POST …/rename`, `PUT …/execution-target`, `PUT …/host` sur le fil | refusé | 400 |
| Fil d'un autre utilisateur (lecture, suppression, chat) | `requireOwned` d'abord | 404 |
| Compte sans Forge | garde existante | 403 |

## Contraintes de validation
| Champ | Règle |
|---|---|
| `jarvis_thread_user_id` | `NULL` ou égal à `user_id` ; `UNIQUE` |
| nom | fixe « Jarvis », non modifiable |

## Critères d'acceptation
- [x] Deux appels concurrents ne créent qu'un fil (8 appels concurrents, test).
- [x] Le fil n'apparaît pas dans l'univers du cerveau rapide (ni dans « Hébergé »).
- [x] Il est en tête de l'Atelier.
- [x] Le supprimer ou le renommer donne 400.
- [x] L'utilisateur B reçoit 404 sur le fil de A.
- [x] La consigne système d'un tour dans le fil Jarvis est identique à l'octet à celle d'un sujet hébergé (test, tous les modes).

## Plan de test
- **Intégration** `JarvisThreadApiIntegrationTest` : création unique puis même id ; 8 créations concurrentes ; exclusions univers/Hébergé ; tête de liste ; 400 sur supprimer/renommer/cible ; **isolation A/B** (404 lecture, suppression, chat ; liste de B sans le fil de A ; deux fils distincts) ; purge.
- **Unitaire** `AtelierChatServiceJarvisThreadPromptTest` : consigne système et panoplie identiques à un sujet hébergé.
- **Frontend** `atelier.component.spec.ts` (+3) : tête de liste, icône, pas de menu ; création puis relecture ; pas de relecture si déjà là.
- Suites complètes backend et frontend + build.

## Impact
- **Table** `workspaces` : colonne `jarvis_thread_user_id uuid` + contrainte `uq_workspaces_jarvis_thread_user` (migration 245, réversible).
- **Endpoint** nouveau : `GET /api/jarvis/thread`. Modifié (additif) : `GET /api/workspaces` (`jarvisThread`, ordre).
- **Backend** : `Workspace`, `WorkspaceRepository`, `WorkspaceService`, `AtelierController`, `WorkspaceSummaryResponse`, `JarvisUniverseService`, `JarvisActivityService`, `RoutineService`, `PosteTurnsService`, `jarvis/thread/*` (nouveau).
- **Frontend** : `atelier.models.ts`, `jarvis-thread.service.ts` (nouveau), `atelier.component.{ts,html}`, `jarvis.component.ts`.

**Préoccupation transversale « Contexte tenant »** — composants qui résolvent un espace : `WorkspaceService.requireOwned` (inchangé, 404), `JarvisUniverseService` (exclut), `JarvisFastContextBuilder` (passe par l'univers : exclu), `RoutineTurnRunner`/`RoutineService` (aucune routine possible), écrans de sujets (pas de poste : `listWithoutHost` l'exclut), `AccountService` (purge, test). Aucune autre préoccupation (auth, plans, navigation : aucune route ni garde ajoutée).

## Arbitrages (documentés dans la PR)
- **Une colonne `uuid` unique plutôt qu'un booléen** (`host_terminal`/`teams_terminal`) : un booléen ne peut porter l'unicité par compte sans index partiel, absent de H2 (base des tests). Réversible (rollback).
- **Création à la première ouverture de l'Atelier ou de `GET /api/jarvis/thread`**, pas dans `GET /api/workspaces` (une lecture qui écrit, et neuf tests de liste à recompter).
- **Source `ARCHIVE` + cible `SANDBOX`** : le patron d'un sujet hébergé, condition de l'égalité de la consigne.
- La garde de suppression est **dans le contrôleur**, pas dans `WorkspaceService.delete` : la purge du compte passe par `delete`.

## Hors périmètre
Le routage vers le fil (SF-245-02) ; la ligne-lien (SF-245-03) ; le volet « Fil » de `/jarvis` (SF-245-04) ; la migration des messages passés (D12).
