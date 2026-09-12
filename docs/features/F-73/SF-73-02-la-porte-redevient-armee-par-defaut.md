# Mini-spec — F-73 / SF-73-02 — La porte de confirmation redevient armée par défaut

---

## Identifiant

`F-73 / SF-73-02`

## Feature parente

`F-73` — Le runner n'est plus confiné

## Statut

`ready`

## Date de création

2026-09-12

## Branche Git

`feat/SF-73-02-porte-armee-par-defaut`

---

## Objectif

> Qu'un projet **nouvellement créé** demande l'autorisation avant d'exécuter une commande
> (`agent_ask_before_bash = true` à la création), les projets **existants** gardant strictement le
> réglage qu'ils portent.

---

## Déclencheur

Décision du PO du 2026-09-12 (cadrage F-73, D3/D4). Elle **annule le défaut de SF-47-04**, pris le
2026-09-10 alors que le confinement *paraissait* exister : la porte était le second verrou d'un
dispositif qui, vérification faite, n'en avait qu'un — et pas celui qu'on croyait. Le confinement
retiré (SF-73-01), la porte redevient **la** garde qui s'interpose avant une commande.

---

## Comportement attendu

### Cas nominal

**(1) Un projet neuf demande.** `WorkspaceService.createLocal()` crée le projet avec
`agentAskBeforeBash = true`. La première commande du premier tour ouvre l'invite d'autorisation
(F-33 / SF-33-03, peinte par F-47).

**(2) Les trois sources sont alignées** (A5). Le défaut est porté par l'**entité** (`@Builder.Default
private boolean agentAskBeforeBash = true`) et par la **colonne** (`DEFAULT TRUE`), si bien que les
projets créés depuis une **archive** (`createFromArchive`) et depuis un **dépôt Git**
(`createFromGit`) héritent du même régime, sans ligne de code par chemin de création.

**(3) Les projets existants ne sont pas modifiés** (D4). La migration **change le défaut de la
colonne**, elle ne fait **aucun `UPDATE`** : un projet qui avait éteint la porte la garde éteinte,
un projet qui l'avait allumée la garde allumée.

**(4) Le réglage reste celui de l'utilisateur.** `PATCH` du réglage
(`AtelierSessionService.setAskBeforeBash`) inchangé : la porte s'éteint et se rallume par projet,
depuis l'en-tête du terminal. La bascule de cible d'exécution ne la réarme **toujours pas** (retrait
de D7 de SF-38-08 par SF-47-04 : **conservé**, un réarmement dans le dos reste un réarmement dans le
dos).

**(5) Ce qui ne bouge pas.** Politique d'outils ouverte à la session (`SessionPermissions.of(...)`),
raccourci « Tout autoriser pour ce message » (SF-38-20), journal d'audit (`runner_audit`),
coupe-circuit : **inchangés**.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Création d'un projet sans nom / nom > 255 caractères | Refus de validation, inchangé | 400 |
| `PATCH` du réglage sur le projet d'un **autre utilisateur** | Traité comme inexistant | 404 |
| Utilisateur non authentifié | Refus standard | 401 |
| Rollback de la migration | Le défaut de colonne revient à `false` ; **aucune donnée réécrite** | — |

---

## Critères d'acceptation

1. `createLocal()` produit un projet dont `agentAskBeforeBash` vaut **`true`**.
2. `createFromArchive()` et `createFromGit()` produisent eux aussi `true` (défaut d'entité).
3. Un `INSERT` SQL qui **omet** la colonne pose `true` (défaut de colonne), en PostgreSQL comme en H2.
4. La migration ne contient **aucun `UPDATE`** : un projet existant à `false` reste à `false` après
   migration.
5. `setAskBeforeBash(userId, id, false)` éteint la porte ; une relecture rend `false`.
6. `setAskBeforeBash` sur le projet d'un autre utilisateur rend **404** et ne modifie rien.
7. `WorkspaceDetailResponse.askBeforeBash` rend `true` pour un projet neuf — c'est ce que l'écran lit.
8. `AtelierChatService` demande bien la confirmation pour l'outil `bash` d'un projet neuf, et pour
   lui seul (aucun autre outil n'est passé par la porte).
9. `mvn -pl backend test` est **vert**.

---

## Périmètre

### Hors scope (explicite)

- Étendre la porte aux **outils fichiers** : risque assumé par le PO (cadrage, D8).
- Modifier les projets existants (D4).
- Toucher au journal d'audit ou au coupe-circuit (D7).
- L'affichage de l'invite : c'est **SF-73-03**.

---

## Valeurs initiales

| Champ | Valeur à la création | Avant |
|---|---|---|
| `workspaces.agent_ask_before_bash` | `true` | `false` (SF-47-04) |

---

## Contraintes de validation

| Champ | Contrainte | Motif |
|---|---|---|
| `agent_ask_before_bash` | `boolean`, `NOT NULL`, défaut `true` | pas de troisième état : la porte est armée ou non |

---

## Technique

### Endpoint(s)

Aucun endpoint créé ni modifié. Changent de **valeur par défaut** dans les réponses existantes :
`POST /api/workspaces` (toutes sources) et `GET /api/workspaces/{id}` (`askBeforeBash`).

### Tables impactées

`workspaces` — colonne `agent_ask_before_bash` : **défaut** modifié, type et nullabilité inchangés.

### Migration Liquibase

`backend/src/main/resources/db/changelog/migrations/072-workspaces-ask-before-bash-default.xml`
(`072` = premier numéro libre après `071-live-terminals.xml`).
Deux changesets (`postgresql`, `h2`), chacun un `addDefaultValue` avec `rollback` vers `false`.
**Aucun `UPDATE`** : les lignes existantes ne sont pas touchées.

### Composants Angular (si applicable)

Aucun.

---

## Plan de test

### Tests unitaires

1. `WorkspaceServiceTest` : `createLocal` → `agentAskBeforeBash == true`.
2. Même vérification sur `createFromArchive` et `createFromGit` (défaut d'entité).
3. `new Workspace()` / `Workspace.builder().build()` → `true` (le défaut n'est pas qu'en base).

### Tests d'intégration

4. Test d'intégration existant sur la création de projet : la réponse `WorkspaceDetailResponse`
   porte `askBeforeBash: true`.
5. Test de migration : sur une base H2 fraîche, un `INSERT` sans la colonne donne `true` ; une ligne
   posée à `false` avant migration reste à `false` après (lecture du changelog, pas d'`UPDATE`).
6. `RunnerGuardrailsApiIntegrationTest` : le réglage reste modifiable par `PATCH`, et le projet d'un
   autre utilisateur rend 404.

### Isolation workspace

7. Toute lecture ou écriture du réglage passe par `requireOwned(userId, id)` — déjà en place,
   couvert par le test 6 : le projet d'autrui est **inexistant**, jamais « interdit ».

---

## Dépendances

### Subfeatures bloquantes

Aucune. SF-73-01 (runner) est indépendante ; SF-73-03 (frontend) dépend de celle-ci.

### Questions ouvertes impactées

- **OQ-14** — rouverte par le constat de F-73, **retranchée le 2026-09-12** : la porte est de
  nouveau armée par défaut. `ADR-018` est **supersédé par ADR-019**.

---

## Notes et décisions

- **Préoccupation transversale — « Plans / limites » : non.** Le réglage n'est ni un quota ni un
  gate de plan ; il n'est lu qu'à l'**ouverture de session** (`AtelierSessionService`, ligne 507) et
  par `AtelierChatService.requiresConfirmation`. Ces deux points sont les seuls consommateurs, et ils
  ne changent pas.
- **Le défaut est posé à deux endroits, et c'est voulu** : l'entité (pour tout code Java qui crée un
  projet) **et** la colonne (pour tout `INSERT` qui omettrait le champ). Un seul des deux laisserait
  un chemin à `false` sans que personne ne le voie.
