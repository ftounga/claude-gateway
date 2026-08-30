# Mini-spec — F-11 / SF-11-03 — La suppression d'un compte efface réellement tout

## Identifiant
`F-11 / SF-11-03`

## Feature parente
`F-11` — Settings & compte (export / suppression des données, RGPD)

## Statut
`ready`

## Date de création
2026-08-30

## Branche Git
`feat/SF-11-03-purge-complete-suppression-compte`

---

## Objectif

> `DELETE /account` efface **toutes** les données de l'utilisateur : au-delà de ce qui est déjà purgé,
> ses workspaces d'Atelier (lignes **et** fichiers dans le stockage objet), ses messages d'Atelier,
> ses documents OCR, leurs chunks d'embeddings et les liens de bibliothèque associés.

---

## Le défaut constaté

Le schéma n'a **aucune clé étrangère vers `users`** : toute purge est explicite. `deleteAccount`
couvrait messages, conversations, fichiers téléversés, compteurs, abonnement, clés API, jeton GitHub,
templates, et — depuis SF-38-14 — le domaine runner. **Restent derrière après suppression** :

| Table | Contenu abandonné |
|---|---|
| `workspaces` | le projet lui-même, **plus tous ses fichiers dans S3** (préfixe `userId/workspaceId`) |
| `atelier_messages` | l'historique des sessions d'agent (messages, transcriptions de terminal) |
| `documents` | documents OCR, y compris le **texte extrait** et la réponse brute du fournisseur |
| `chunks` | les embeddings calculés à partir de ces documents |
| `message_library_documents` | les liens bibliothèque ↔ message, orphelins une fois les messages supprimés |

Découverte annexe : `WorkspaceService.delete` (suppression d'un **seul** workspace par l'utilisateur)
supprime les fichiers et la ligne, mais **pas** les `atelier_messages` de ce workspace — les mêmes
orphelins s'accumulent donc déjà hors suppression de compte. Corrigé ici aussi.

**Hors périmètre justifié** : `atelier_agent_config` ne porte ni `user_id` ni `workspace_id` — c'est
une configuration globale d'environnement d'agent, pas une donnée personnelle.

---

## Comportement attendu

### Cas nominal
1. `DELETE /account` supprime, dans la **même transaction** que la purge existante : les liens de
   bibliothèque, les chunks, les documents, les messages d'Atelier, puis les workspaces.
2. Pour chaque workspace, les fichiers du stockage objet sont supprimés (`deletePrefix`) **avant** la
   ligne, afin qu'aucun objet ne devienne inatteignable si la transaction échoue ensuite.
3. La suppression d'un **seul** workspace (`WorkspaceService.delete`) supprime désormais aussi ses
   `atelier_messages`.
4. Rien de ce qui appartient à un autre utilisateur n'est touché.

### Cas d'erreur

| Situation | Comportement attendu |
|---|---|
| Aucun workspace / document / message | Suppression sans effet, aucune erreur |
| Le stockage objet est indisponible | L'exception remonte : la transaction est annulée, le compte n'est pas à moitié supprimé |
| Un workspace sans aucun fichier | `deletePrefix` sur un préfixe vide ne lève pas |
| Deux suppressions concurrentes | Comportement actuel conservé (`findByIdOrThrow`) |

---

## Critères d'acceptation

- [ ] Après `DELETE /account` : plus aucune ligne `workspaces`, `atelier_messages`, `documents`,
      `chunks`, `message_library_documents` pour cet utilisateur.
- [ ] Les fichiers de chaque workspace sont supprimés du stockage objet.
- [ ] `WorkspaceService.delete` supprime les `atelier_messages` du workspace supprimé.
- [ ] Isolation : les données d'un second utilisateur restent **strictement intactes** (testé).
- [ ] La suppression reste **tout ou rien** : une erreur de stockage annule l'ensemble.
- [ ] Le test de purge existant échoue si l'un des nouveaux repositories est oublié.
- [ ] Suite backend verte (1104 au départ).

---

## Périmètre

### Hors scope (explicite)
- `atelier_agent_config` : configuration globale, aucune donnée personnelle (justifié ci-dessus).
- L'**export** RGPD (`AccountExport`) : il n'inclut ni workspaces, ni documents OCR, ni audit runner.
  C'est le pendant « droit d'accès » du même sujet, mais il a son propre format et sa propre
  subfeature — **à ouvrir**, ce n'est pas refermé ici.
- La rétention par ancienneté (purge de données de comptes **vivants**) : sujet distinct.

---

## Technique

| Fichier | Nature |
|---|---|
| `account/AccountService.java` | purge étendue, ordre explicite |
| `atelier/AtelierMessageRepository.java` | `deleteByUserId`, `deleteByWorkspaceId` |
| `atelier/WorkspaceRepository.java` | `findByUserId` (si absent), `deleteByUserId` |
| `ocr/DocumentRepository.java` | `deleteByUserId` |
| `rag/ChunkRepository.java` | `deleteByUserId` |
| `chat/MessageLibraryDocumentRepository.java` | suppression des liens par documents de l'utilisateur |
| `atelier/WorkspaceService.java` | suppression des messages du workspace supprimé |

### Migration Liquibase
- [x] Non applicable — aucune modification de schéma.

---

## Plan de test

### Tests unitaires
- [ ] `deleteAccount` appelle chaque purge, dans l'ordre, avant la suppression de l'utilisateur.
- [ ] Chaque workspace voit son préfixe de stockage supprimé.
- [ ] `WorkspaceService.delete` supprime les messages du workspace.

### Tests d'intégration
- [ ] Deux utilisateurs dotés de workspaces, messages d'Atelier, documents et chunks : après
      `DELETE /account` du premier, plus aucune de ses lignes, **toutes** celles du second intactes.

### Isolation
- [x] Applicable et testée (deux utilisateurs).

---

## Dépendances
`SF-38-14` (purge du domaine runner) — **done**. Aucune question ouverte impactée.

## Préoccupation transversale
Aucune : ni auth, ni contexte tenant, ni plan/limite, ni routage. Le changement est confiné au chemin
de suppression de compte et à la suppression d'un workspace.
