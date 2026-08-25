# Mini-spec — [F-34 / SF-34-01] Lecture des instructions du projet et injection à l'ouverture de session

## Identifiant

`F-34 / SF-34-01`

## Feature parente

`F-34` — Instructions par projet (CLAUDE.md du workspace)

## Statut

`done`

## Date de création

2026-08-25

## Branche Git

`feat/SF-34-01-instructions-projet`

---

## Objectif

À l'ouverture d'une session d'exécution, lire le fichier d'instructions du projet
(`CLAUDE.md`, repli `.atelier/instructions.md`) et l'**ajouter** au prompt système plateforme via
`agent_with_overrides`, afin que l'agent connaisse les conventions du projet sur lequel il travaille.

---

## Comportement attendu

### Cas nominal

1. Un tour d'exécution ouvre une session (F-30 SF-30-04 : à la première demande, ou après
   réinitialisation / expiration).
2. Avant l'appel de création de session, le backend résout le fichier d'instructions du workspace
   **du propriétaire** (isolation `user_id`) :
   - projet **ARCHIVE** : lecture dans le stockage objet (`WorkspaceService.readFile`) ;
   - projet **GIT** : lecture locale si la session a déjà réécrit le fichier, sinon sur la branche
     montée (`GitWorkspaceService.readFile`, API GitHub, jeton du propriétaire) ;
   - ordre de résolution : `CLAUDE.md` d'abord, sinon `.atelier/instructions.md`.
3. Le contenu est **borné** à `app.atelier.agent.max-instructions-chars` (défaut **20 000**). Au-delà,
   il est tronqué et la troncature est **mentionnée en clair** dans le prompt (« […] instructions
   tronquées à N caractères »).
4. Le prompt système envoyé à la session est composé :
   `prompt plateforme` + un cadre annonçant que ce qui suit est **fourni par l'utilisateur**, ne peut
   ni annuler ni contredire les règles de la plateforme + le contenu du fichier.
   Il est passé dans le corps de création de session sous
   `agent: {type: "agent_with_overrides", id: <agentId>, system: <prompt composé>}`.
5. Aucun fichier d'instructions → le corps de création reste **identique à aujourd'hui**
   (`agent: "<agentId>"`), donc aucune régression pour les projets existants.
6. Une modification du fichier ne prend effet qu'à la **session suivante** : la session persistante
   fige son prompt à l'ouverture (D5 du cadrage).

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Aucun fichier d'instructions | Session ouverte sans surcharge, comportement actuel | 200 |
| Fichier présent mais illisible (binaire, trop volumineux, dépôt/GitHub indisponible) | **Best-effort** : session ouverte sans instructions, trace en `debug`, jamais d'échec du tour | 200 |
| Fichier plus long que la borne | Contenu tronqué + mention explicite dans le prompt | 200 |
| Workspace d'un autre utilisateur | Refus avant tout appel fournisseur (`requireOwned`) | 404 |
| Projet Git sans jeton GitHub enregistré | Comportement inchangé (l'ouverture de session Git échoue déjà pour cette raison) | 400 |

---

## Critères d'acceptation

- [ ] Un workspace portant `CLAUDE.md` ouvre sa session avec un prompt système composé contenant
      **le prompt plateforme** puis **le contenu du fichier**.
- [ ] Le prompt plateforme est **en tête** et n'est jamais remplacé (protection injection, D2).
- [ ] `.atelier/instructions.md` est utilisé **uniquement** si `CLAUDE.md` est absent.
- [ ] Sans fichier d'instructions, la création de session est **celle d'avant** (`agent` =
      identifiant en chaîne, aucune surcharge).
- [ ] Un contenu au-delà de la borne est tronqué et la troncature est annoncée dans le prompt.
- [ ] Un fichier illisible (exception de lecture) n'empêche jamais l'ouverture de la session.
- [ ] Sur un projet Git, le fichier est lu sur la branche montée quand il n'existe pas localement.
- [ ] `GET /api/workspaces/{id}` expose `instructionsPath` (`null` si aucun fichier).
- [ ] Isolation : la résolution passe exclusivement par des services filtrant sur `user_id`.
- [ ] Aucun secret ni contenu de jeton journalisé.

---

## Périmètre

### Hors scope (explicite)

- Instructions par dossier (imbrication à la Claude Code).
- Édition assistée du fichier ; création d'un `CLAUDE.md` par la plateforme.
- Instructions au niveau utilisateur, transversales à tous ses projets.
- Rechargement à chaud du prompt d'une session déjà ouverte (D5).
- Affichage à l'écran → **SF-34-02**.

---

## Valeurs initiales

| Champ | Valeur initiale | Règle |
|-------|----------------|-------|
| `app.atelier.agent.max-instructions-chars` | `20000` | Borne du contenu injecté (D3), configurable |
| `instructionsPath` (DTO) | `null` | Aucun fichier d'instructions détecté dans l'arborescence |

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| contenu des instructions | Non | 20 000 caractères (configurable) | texte libre | — | troncature + mention |
| chemin d'instructions | Non | — | `CLAUDE.md` \| `.atelier/instructions.md` | — | chemin relatif, sans `/` initial |

Notes :
- Un contenu **vide ou blanc** est traité comme une absence de fichier (aucune surcharge).
- La borne compte des **caractères** (pas des tokens) : mesure simple, vérifiable, suffisante pour
  éviter qu'un fichier démesuré consomme le contexte utile.

---

## Technique

### Contrat API (figé — importé par SF-34-02)

`GET /api/workspaces/{id}` — réponse `WorkspaceDetailResponse` **additive** :

```json
{
  "id": "…", "name": "…", "fileCount": 12, "files": ["CLAUDE.md", "src/main.ts"],
  "createdAt": "…", "source": "GIT", "gitRepoUrl": "…", "gitRepo": "owner/repo",
  "gitBranch": "main", "truncated": false,
  "instructionsPath": "CLAUDE.md"
}
```

| Champ | Type | Sémantique |
|-------|------|-----------|
| `instructionsPath` | `string \| null` | Chemin relatif du fichier d'instructions qui sera pris en compte **à la prochaine ouverture de session**. `null` = aucun. Dérivé de l'arborescence renvoyée (`CLAUDE.md` prioritaire sur `.atelier/instructions.md`). |

Le champ est également présent sur les réponses `POST /api/workspaces`,
`POST /api/workspaces/{id}/file/rename`, `POST /api/workspaces/{id}/import-library` et
`POST /api/workspaces/git`, qui renvoient le même DTO. Aucun autre endpoint n'est modifié, aucun
champ existant n'est retiré ni renommé.

### Endpoint(s)

| Méthode | URL | Auth | Rôle minimum |
|---------|-----|------|-------------|
| GET | `/api/workspaces/{id}` | Oui | Gold (gating Atelier existant) |

Aucun nouvel endpoint.

### Tables impactées

| Table | Opération | Notes |
|-------|-----------|-------|
| — | — | Aucune : les instructions vivent dans les fichiers du projet, pas en base |

### Migration Liquibase

- [ ] Oui
- [x] **Non applicable** — aucun changement de schéma.

### Composants Angular (si applicable)

- Aucun (SF-34-02).

---

## Plan de test

### Tests unitaires

- [ ] `ProjectInstructionsServiceTest` — `CLAUDE.md` présent → résolu avec son contenu.
- [ ] `ProjectInstructionsServiceTest` — `CLAUDE.md` absent, `.atelier/instructions.md` présent → repli.
- [ ] `ProjectInstructionsServiceTest` — les deux présents → `CLAUDE.md` gagne.
- [ ] `ProjectInstructionsServiceTest` — aucun fichier → vide.
- [ ] `ProjectInstructionsServiceTest` — contenu blanc → vide (pas de surcharge inutile).
- [ ] `ProjectInstructionsServiceTest` — contenu > borne → tronqué + mention.
- [ ] `ProjectInstructionsServiceTest` — lecture qui lève (dépôt indisponible) → vide, aucune exception.
- [ ] `ProjectInstructionsServiceTest` — projet Git → lecture déléguée à `GitWorkspaceService`.
- [ ] `AgentSystemPromptTest` — le prompt composé commence par le prompt plateforme et encadre le
      contenu utilisateur comme non prioritaire.
- [ ] `AtelierSessionServiceTest` — instructions présentes → `createSession` reçoit le prompt composé.
- [ ] `AtelierSessionServiceTest` — pas d'instructions → `createSession` reçoit `null` en surcharge.
- [ ] `AtelierSessionServiceTest` — projet Git avec instructions → surcharge transmise avec le dépôt.
- [ ] `AnthropicManagedAgentProviderTest` — surcharge nulle → corps `"agent": "<id>"` (inchangé).
- [ ] `AnthropicManagedAgentProviderTest` — surcharge non nulle → corps
      `"agent": {"type": "agent_with_overrides", "id": …, "system": …}`.

### Tests d'intégration

- [ ] `GET /api/workspaces/{id}` → `instructionsPath = "CLAUDE.md"` sur un projet qui en porte un.
- [ ] `GET /api/workspaces/{id}` → `instructionsPath = null` sinon.

### Isolation workspace

- [x] Applicable — la lecture passe par `WorkspaceService`/`GitWorkspaceService`, qui exigent un
      workspace **possédé** ; les tests d'intégration existants vérifient déjà qu'un workspace d'un
      autre utilisateur reste en 404 sur `GET /api/workspaces/{id}`.

---

## Dépendances

### Subfeatures bloquantes

- `SF-30-04` (session persistante) — done
- `SF-31-02/03` (projets Git, lecture de fichier sur branche) — done

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- **D1/D2/D3/D4/D5 du cadrage** repris tels quels (`docs/features/F-34/CADRAGE.md`).
- **Arbitrage (réversible)** : la surcharge `system` de `agent_with_overrides` **remplace** le prompt
  de l'agent chez le fournisseur. Pour respecter D2 (« ajout, jamais substitution »), la composition
  est faite **côté Gateway** : le prompt plateforme est réémis en tête, suivi du contenu du projet.
  Alternative écartée : envoyer le seul contenu utilisateur en surcharge (perte des garde-fous).
- **Arbitrage (réversible)** : un fichier illisible n'échoue **pas** le tour. Alternative écartée :
  refuser l'ouverture de session — bloquer le travail pour un fichier annexe serait disproportionné.
- **Provider Independence** : la surcharge est portée par l'interface `ManagedAgentProvider`
  (paramètre `systemOverride`), pas par un appel Anthropic depuis le domaine.
