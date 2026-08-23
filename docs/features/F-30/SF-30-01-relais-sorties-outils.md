# Mini-spec — [F-30 / SF-01] Relais des sorties de commandes

---

## Identifiant

`F-30 / SF-01`

## Feature parente

`F-30` — Atelier — expérience terminal

## Statut

`ready`

## Date de création

2026-08-23

## Branche Git

`feat/SF-30-01-relais-sorties-outils`

---

## Objectif

> En une phrase : que fait cette subfeature ?

Transmettre au frontend la **sortie** des commandes exécutées par l'agent (`agent.tool_result`), aujourd'hui jamais relayée, en plus de la commande elle-même.

---

## Contexte

`ManagedEventListener` n'expose que `onAgentText` (`agent.message`), `onAction` (`agent.tool_use`) et `onStatus`. Les events **`agent.tool_result` sont ignorés** par la boucle de polling du provider. L'écran affiche donc `npm test` sans jamais montrer ce que la commande a produit — alors que voir le retour des commandes est l'essentiel de l'expérience terminal (ADR-014).

Cette subfeature est la **fondation backend** du rendu terminal (SF-30-02) : sans elle, le frontend n'a rien à afficher.

---

## Comportement attendu

### Cas nominal

1. L'agent exécute un outil (`bash`, lecture, écriture…). Le provider émet déjà `onAction(tool, detail)`.
2. Quand l'event **`agent.tool_result`** correspondant arrive, le provider notifie `onActionResult(...)` avec : nom de l'outil, identifiant de l'appel (`tool_use_id`) s'il est présent, sortie textuelle, et indicateur d'erreur.
3. Le contrôleur relaie un nouvel événement SSE **`action_result`**.
4. Les événements existants (`agent`, `action`, `status`, `done`, `error`) sont **inchangés** : un frontend qui ignore `action_result` continue de fonctionner exactement comme avant.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Event `tool_result` de forme inattendue | Parsing **défensif** : la sortie est cherchée dans plusieurs emplacements plausibles ; à défaut, chaîne vide — **jamais d'exception**, le run continue |
| Sortie volumineuse (`npm install`) | **Tronquée côté backend** à une borne configurable, avec indication du nombre de caractères omis. Une sortie de plusieurs Mo ne doit ni saturer le flux SSE ni le navigateur |
| Outil en échec (code de retour non nul) | Relayé avec `error: true` ; le frontend pourra le distinguer visuellement |
| `tool_use_id` absent | Relayé à `null` ; le frontend rattachera alors au dernier appel connu |
| Client déconnecté pendant l'émission | Même traitement que les autres événements : `StreamAbortedException`, run interrompu proprement |

---

## Critères d'acceptation

- [ ] `ManagedEventListener` et `AtelierAgentListener` exposent `onActionResult(tool, toolUseId, output, error)`, en méthode `default` no-op (aucune régression pour les appelants existants)
- [ ] Le provider traite les events `agent.tool_result` **et** `agent.mcp_tool_result` dans sa boucle de polling, avec la même déduplication par `id` que les autres events
- [ ] L'extraction de la sortie est défensive : `content` (texte ou blocs), `output`, `result`, `stdout`/`stderr` — aucune forme inattendue ne lève d'exception
- [ ] La sortie est tronquée au-delà d'une borne **configurable** (`app.atelier.agent.max-tool-output-chars`, défaut 10 000), avec une mention explicite du nombre de caractères omis
- [ ] Un événement SSE `action_result` est émis, portant `tool`, `toolUseId`, `output`, `error`
- [ ] Les événements SSE existants sont **inchangés** (noms et charges utiles)
- [ ] Aucun endpoint créé ou modifié, aucune table, aucune migration
- [ ] Isolation `user_id` inchangée : le relais s'exécute dans le run déjà borné par `requireOwned`
- [ ] Tests unitaires sur l'extraction (formes valides, formes inattendues, troncature) et sur le relais SSE

---

## Périmètre

### Hors scope

- **Affichage** de ces sorties → SF-30-02
- Renommage des modes et mise en valeur Gold → SF-30-03
- Session persistante → SF-30-04
- Streaming *incrémental* d'une sortie longue au fil de son écriture : les events `tool_result` arrivent complets, il n'y a pas de flux partiel à relayer

---

## Contraintes de validation

| Élément | Contrainte |
|---------|-----------|
| Borne de sortie | Configurable, défaut **10 000 caractères** ; troncature avec mention du volume omis |
| `toolUseId` | Optionnel (`null` accepté) |
| Nom d'outil | Reprend le repli existant : `"tool"` si absent |
| Compatibilité | Les événements SSE existants ne changent ni de nom ni de forme |

---

## Technique

### Endpoint(s)

Aucun créé ni modifié. L'événement `action_result` s'ajoute au flux de `POST /workspaces/{id}/agent/stream`.

### Tables impactées / Migration

Aucune.

### Fichiers impactés

| Fichier | Nature |
|---------|--------|
| `atelier/agent/ManagedEventListener.java` | + `onActionResult` (default no-op) |
| `atelier/agent/AtelierAgentListener.java` | + `onActionResult` (miroir applicatif, Provider Independence) |
| `atelier/agent/AnthropicManagedAgentProvider.java` | Traitement de `agent.tool_result` / `agent.mcp_tool_result` + extraction défensive + troncature |
| `atelier/agent/AtelierSessionService.java` | Passe-plat du nouvel événement vers le listener applicatif |
| `atelier/AtelierAgentController.java` | + `sendActionResult` et record `StreamActionResult` |
| `atelier/agent/AtelierAgentProperties.java` | + `max-tool-output-chars` |
| `resources/application.yml` | + valeur par défaut |

---

## Plan de test

### Tests unitaires

- [ ] Extraction : `content` textuel, `content` en blocs, `output`, `result`, `stdout`+`stderr`
- [ ] Extraction : event vide / champs absents / type inattendu → chaîne vide, **aucune exception**
- [ ] Troncature : sortie au-delà de la borne → tronquée + mention du volume omis
- [ ] Indicateur d'erreur : `is_error` à vrai → `error: true`
- [ ] Déduplication : un même event `tool_result` lu deux fois n'est notifié qu'une fois

### Tests d'intégration

- [ ] `POST /workspaces/{id}/agent/stream` émet un événement `action_result` après un `action`
- [ ] Les événements existants (`agent`, `action`, `status`, `done`) restent identiques — **non-régression explicite**
- [ ] Isolation : workspace d'un autre utilisateur → `error: workspace_not_found`, aucun event d'exécution

### Isolation utilisateur

- [x] **Applicable** — test conservé : un utilisateur ne peut pas ouvrir de flux d'exécution sur le workspace d'un autre. Le relais n'introduit aucun nouveau chemin d'accès aux données : il s'exécute à l'intérieur d'un run déjà borné par `requireOwned`.

---

## Préoccupations transversales

| Préoccupation | Concernée | Analyse |
|--------------|-----------|---------|
| Auth / Principal | **Non** | Aucun changement d'authentification ; l'identité reste résolue en amont du flux. |
| Contexte tenant | **Non** | Aucun nouveau chemin d'accès aux données ; `requireOwned` inchangé. |
| Plans / limites | **Non** | Aucun appel aux services de quota ajouté ou modifié ; le gating Gold et les plafonds sandbox sont inchangés. |
| Navigation / routing | **Non** | Aucune route, aucun endpoint. |

---

## Dépendances

- Aucune subfeature bloquante. SF-30-02 (affichage) dépend de celle-ci.

---

## Notes et décisions

- **Forme exacte de `agent.tool_result` non documentée** dans les sources consultées : d'où le parsing défensif à emplacements multiples, aligné sur le style déjà en place (`extractText`, `toolDetail` acceptent plusieurs formes). Un changement de forme côté API dégrade l'affichage, il ne casse pas le run.
- **Troncature côté backend, pas seulement côté affichage** : un `npm install` produit des dizaines de milliers de lignes ; les laisser traverser le flux SSE saturerait le navigateur avant même d'être affichées.
- **Rétrocompatibilité stricte** : nouvel événement ajouté, aucun existant modifié — le frontend actuel continue de fonctionner sans changement, ce qui permet de livrer et déployer cette subfeature indépendamment de SF-30-02.
