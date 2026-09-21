# Mini-spec — F-141 / SF-141-03 Créer le sujet + gouvernance héritée, sur validation

## Identifiant
`F-141 / SF-141-03`

## Feature parente
`F-141` — L'aiguilleur de sujet à la racine

## Statut
`in-progress`

## Date de création
2026-09-22

## Branche Git
`feat/SF-141-03-creer-sujet`

---

## Objectif
Sur validation « nouveau sujet », l'agent **crée le dossier** sous la racine du poste et **hérite la gouvernance** (STATE.md/PLAN-ACTION.md/skills) via le **semis existant et idempotent**, exactement comme un projet créé par l'écran — puis y dépose l'info.

---

## Comportement attendu

### Cas nominal
1. Au **terminal du poste**, après validation « nouveau sujet », l'agent appelle l'outil `create_subject` avec le nom du dossier.
2. La gateway crée le **projet-dossier** sous la racine via `WorkspaceService.openOnHost(userId, hostId, name, …)` — le **chemin de création existant** de l'écran.
3. La création émet `WorkspaceCreatedEvent` → `GovernanceWorkspaceCreatedListener` → `GovernanceDepositService.depositOnNewProjectQuietly` : **semis idempotent** (`GovernanceDepositMode.CREATE_ONLY`) de STATE.md/PLAN-ACTION.md/skills — **crée ce qui manque, n'écrase jamais**.
4. L'outil rend le chemin créé et confirme l'héritage ; l'agent **dépose ensuite l'info** dans le sujet avec `write_file`.

### Cas d'erreur / limites
| Situation | Comportement attendu |
|-----------|----------------------|
| Le dossier/sujet existe déjà (workspace au même chemin) | `HostProjectExistsException` → message clair « existe déjà, dépose l'info dedans ou reclasse » ; **rien n'est écrasé** |
| Nom vide/blanc | Refus explicite avec action corrective (« donne un nom de dossier ») |
| Appel hors terminal du poste | Refus : `create_subject` n'existe qu'à la racine ; répondre sans lui |
| Poste non gouverné | Le dossier est créé ; l'héritage ne pose que ce que le poste porte (rien si aucune activation) — jamais un échec de création |

---

## Critères d'acceptation
- L'outil `create_subject` est **offert uniquement au terminal du poste** (`isHostTerminal()`), jamais sur un projet ordinaire ni hébergé.
- Un appel valide **délègue à `WorkspaceService.openOnHost(userId, hostId, name, …)`** — donc au chemin de semis existant et idempotent (aucune logique de semis dupliquée).
- Un sujet déjà présent **n'est pas écrasé** : la création refuse le doublon et le semis reste `CREATE_ONLY`.
- **Isolation** : la création porte `user_id` (appelant) + `host_id` (poste possédé du terminal) ; aucun mélange entre clients.
- **Non-régression** : le semis idempotent (`GovernanceDepositServiceTest`), la capture réunion, le Radar, la Vigie, les terminaux par sujet restent inchangés.

---

## Plan de test minimal
- **Unitaire (outil offert)** : `createSubjectToolIsOfferedOnlyOnTheHostTerminal` — présent au terminal du poste, absent sur projet RUNNER et sur projet hébergé.
- **Unitaire (délégation)** : `createSubjectDelegatesToOpenOnHostWithUserAndHost` — un appel `create_subject{name}` invoque `workspaceService.openOnHost(userId, hostId, "…", …)` et rend un succès nommant le sujet.
- **Unitaire (jamais d'écrasement)** : `createSubjectOnAnExistingFolderDoesNotOverwrite` — `HostProjectExistsException` → outcome en erreur avec message d'action, **sans** seconde création.
- **Unitaire (nom vide)** : `createSubjectRejectsABlankName` — refus, `openOnHost` jamais appelé.
- **Réutilisé (idempotence + isolation du semis)** : `GovernanceDepositServiceTest` (CREATE_ONLY, n'écrase pas) et l'isolation `user_id` de `openOnHost`/`listByHost` — cités, non redupliqués.

---

## Tables / endpoints / composants impactés
- `AtelierChatService` : nouvel outil `create_subject` (déclaré dans `buildToolsFull` si `isHostTerminal()`), branche de dispatch dans `executeTool`, méthode `executeCreateSubject`.
- Réutilisé sans modification : `WorkspaceService.openOnHost`, `GovernanceWorkspaceCreatedListener`, `GovernanceDepositService.depositOnNewProjectQuietly`.
- **Aucune** table, **aucun** endpoint HTTP nouveau, **aucune** migration.

## Préoccupations transversales
- **Contexte tenant** : la création résout le tenant par le terminal possédé (`workspace` déjà `requireOwned` dans `chat`) → `user_id` de l'appelant + `host_id = workspace.getHostId()`. Composants qui résolvent le tenant ici : `executeCreateSubject` (seul ajout) → `WorkspaceService.openOnHost` (isolation `listByHost(userId, hostId)` existante). Aucun autre chemin de résolution touché.
- **Auth / Principal** : inchangé.
- **Navigation / routing** : aucune route front.

## Mise à jour runner
**Non.** `create_subject` est un **outil traité par la gateway** (comme radar/email/pages) ; le semis écrit STATE.md/PLAN-ACTION.md via les outils runner **existants** (`governance_map_write`/écriture projet). Aucun nouvel outil runner, aucune mise à jour du binaire runner.

## Hors périmètre
- L'annonce de destination (SF-141-01) et l'aiguillage/proposition (SF-141-02) — déjà livrés.
- Le **reclassement** d'une entrée → SF-141-04.
