# Mini-spec — F-121 / SF-121-14 — Parallélisme lecture seule : la sous-tâche `task` en lecture rejoint le fan-out

## Identifiant

`F-121 / SF-121-14`

## Feature parente

`F-121` — Parité avec Claude Code (boucle maison)

## Statut

`ready`

## Date de création

2026-09-26

## Branche Git

`feat/SF-121-14-parallelisme-lecture-seule`

---

## Objectif

Étendre à la sous-tâche `task` **en lecture seule** le fan-out concurrent d'un même tour déjà livré
pour `explore` (SF-39-21), les sous-tâches qui **mutent** restant strictement sérielles.

---

## Contexte — ce qui existe déjà (vérifié dans le code)

| Brique | État | Où |
|---|---|---|
| Fan-out concurrent des `explore` d'un tour, pool borné, résultats ré-ordonnés par appel | **Livré** (SF-39-21) | `AtelierChatService#exploreConcurrently` (~3478) |
| Réglage du plafond de parallélisme `app.atelier.explore-parallelism` (défaut 3, borné) | **Livré** | `AtelierProperties#exploreParallelism` |
| Doctrine de groupement des explorations indépendantes | **Livré** (SF-39-22 / SF-148-04) | description de l'outil `explore` |
| Sous-boucle `task` **écrivaine** : worktree git isolé, synthèse seule qui remonte, coût imputé au tour | **Livré** (F-150) | `AtelierChatService#task` (~3610), `AtelierTask` |
| `task` **en lecture seule** | **Manquant** | — |

Aujourd'hui un `task` est **toujours** traité comme écrivain : il exige un dépôt git, crée puis
démonte un worktree côté runner, et s'exécute **en série** dans la boucle principale. Quand la
sous-tâche déléguée ne fait que **lire** (audit, revue, inventaire), ce worktree ne sert à rien et la
sérialisation coûte du temps de mur — or « le tour vit dans le flux » (quitter l'écran tue le tour).

---

## Comportement attendu

### Cas nominal

1. Le modèle émet, dans **un même tour**, un ou plusieurs appels `task` portant `read_only: true`
   (éventuellement mélangés à des `explore` et à des `task` écrivains).
2. La boucle principale **isole** les délégations **en lecture** du tour — les `explore` **et** les
   `task` `read_only` — et les exécute **ensemble** via le pool borné existant
   (`app.atelier.explore-parallelism`), dans la limite du plafond de délégations par message
   (`maxDelegations`).
3. Une sous-tâche en lecture seule :
   - reçoit la panoplie **de lecture** (`read_file`, `list_files`, `search_files`, `grep`, `glob`) ;
   - **ne crée aucun worktree** (rien n'écrit : il n'y a rien à isoler) ;
   - tourne sur la consigne système de sous-tâche **en lecture seule** (littéral stable, cache F-134) ;
   - ne rend que sa **synthèse**.
4. Les `task` **sans** `read_only` (ou `read_only: false`) gardent **exactement** le chemin actuel :
   worktree isolé, panoplie complète, exécution **en série**, restitution branche + diff.
5. Chaque conclusion est rattachée à **son** `tool_use` (`callId`) et les `tool_result` sont rendus
   dans **l'ordre des appels**, quel que soit l'ordre de fin.
6. Le coût (entrée/sortie/cache) de **toutes** les sous-boucles est **imputé au tour**, agrégé sur le
   thread principal après jointure du pool.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| `task` `read_only` sans `prompt` (ou vide) | Résultat d'outil en erreur « Consigne (`prompt`) requise pour déléguer une sous-tâche. » ; le tour continue | n/a (outil) |
| `task` `read_only` hors cible RUNNER | Message existant « `task` n'est disponible que sur un poste connecté (runner). » ; aucune sous-boucle lancée | n/a (outil) |
| Panne d'une sous-tâche en lecture (exception, provider) | **Son** résultat d'outil porte l'erreur ; les autres délégations du tour aboutissent | n/a (outil) |
| Plafond de délégations par message atteint | L'appel au-delà du plafond porte « Limite de délégations atteinte pour ce message (N) : poursuis toi-même. » ; rien n'est lancé pour lui | n/a (outil) |
| La sous-boucle en lecture demande un outil mutant (`write_file`, `bash`…) | Résultat d'outil en erreur « Outil indisponible en sous-tâche de lecture : … » ; **rien n'est écrit, rien n'est exécuté** | n/a (outil) |
| Tour interrompu / échéance dépassée | `stop` partagé consulté à chaque itération de chaque sous-boucle ; synthèse partielle ou message d'interruption | n/a (outil) |

---

## Critères d'acceptation

- [ ] Deux `task` `read_only` d'un même tour s'exécutent **concurremment** (démontré par une barrière :
      une exécution en série ne la lèverait pas) et leurs `tool_result` reviennent dans **l'ordre des appels**.
- [ ] Un `explore` et un `task` `read_only` du **même tour** partagent le **même pool** (concurrence
      observée = 2 avec un plafond de 2).
- [ ] Un `task` `read_only` **ne crée ni ne démonte aucun worktree** (`worktreeCreate` jamais appelé).
- [ ] Un `task` **écrivain** du même tour reste **sériel** et crée toujours son worktree (mutations en série).
- [ ] La sous-boucle en lecture ne reçoit **que** des outils de lecture, et un appel mutant y est **refusé**
      (double verrou : panoplie construite + liste blanche à l'exécution).
- [ ] Le coût de **toutes** les sous-boucles en lecture est **imputé au tour** (plafond par message inchangé).
- [ ] Le plafond de parallélisme est respecté (N délégations > plafond → vagues).
- [ ] L'échec d'une délégation en lecture n'affecte pas les autres.
- [ ] La description de l'outil `task` déclare `read_only` et reste un **littéral stable** (cache F-134 intact).
- [ ] Isolation `user_id` / cible d'exécution **inchangées** : la sous-boucle lit par le chemin d'exécution
      existant (`executeTool`), résolu par `(user_id, workspace_id)` du tour.

---

## Périmètre

### Hors scope (explicite)

- Paralléliser des sous-tâches qui **écrivent** ou **exécutent** (worktrees concurrents, invites de
  confirmation surgies d'agents invisibles) — reste **sériel**, conformément au cadrage.
- Nouveau réglage de parallélisme propre à `task` (le plafond existant borne toutes les délégations en lecture).
- Sous-agent récursif : la sous-boucle n'a ni `task` ni `explore` (inchangé).
- Toute UI : aucun écran, aucun composant Angular, aucun nouvel événement de progression.
- Thinking entrelacé (F-121-16), estimateur de jetons (F-121-18) : autres SF.

---

## Valeurs initiales

Sans objet — aucune entité créée, aucune table touchée.

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|---|---|---|---|---|---|
| `task.prompt` | Oui | — | texte non vide après `trim()` | Non | `trim()` |
| `task.path` | Non | — | chemin indicatif de départ | Non | — |
| `task.read_only` | Non | — | booléen ; **absent = `false`** (comportement actuel préservé) | Non | — |
| `app.atelier.explore-parallelism` | Non | — | entier ≥ 1, borné par `MAX_EXPLORE_PARALLELISM` ; défaut 3 | Non | clampé |

---

## Technique

### Endpoint(s)

Aucun. Le changement est **interne à la boucle de tour** (aucun contrôleur, aucun contrat REST touché).

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable — aucun changement de schéma.

### Composants impactés

| Composant | Modification |
|---|---|
| `AtelierChatService` | pré-passage parallèle étendu aux `task` en lecture ; nouvelle voie `readOnlyTask` ; branche `task` de la boucle qui rattache l'issue pré-calculée ; description de l'outil `task` (+ `read_only` + doctrine de groupement) |
| `AtelierTask` | consigne système **lecture seule** (littéral stable) sélectionnée par un paramètre `readOnly` |

### Composants Angular

Aucun — cette SF n'a **pas** de volet frontend (aucun écran, aucun contrat REST) ; la sous-tâche
s'affiche déjà par l'événement d'étape `task` existant.

---

## Plan de test

### Tests unitaires (`AtelierChatServiceParallelReadTaskTest`, nouveau)

- [ ] Deux `task` `read_only` d'un même tour se **recouvrent** (barrière à 2) et reviennent dans l'ordre des appels.
- [ ] `explore` + `task` `read_only` dans le même tour : concurrence observée = 2, deux conclusions rendues.
- [ ] Un `task` `read_only` **ne crée aucun worktree** (`verify(never()).worktreeCreate`).
- [ ] Un `task` écrivain dans le même tour **crée** son worktree et reste sériel.
- [ ] La sous-boucle en lecture ne reçoit que `list_files/read_file/search_files/grep/glob`, et un
      `write_file` demandé par le modèle y est **refusé** (aucun appel runner d'écriture).
- [ ] Le coût des deux sous-boucles concurrentes est **imputé au tour**.
- [ ] Échec d'une délégation en lecture → les autres aboutissent, le tour aboutit.
- [ ] Plafond de parallélisme 1 → pas de recouvrement, les deux aboutissent quand même (vagues).
- [ ] La description de l'outil `task` déclare la propriété `read_only`.

### Tests unitaires (`AtelierTaskTest`, complété)

- [ ] En `readOnly`, la consigne système annonce la lecture seule et n'annonce **pas** l'écriture.

### Tests d'intégration

Sans objet (aucun endpoint). Les tests de la boucle jouent le service complet avec provider et runner
bouchonnés — c'est le niveau d'intégration existant pour cette brique.

### Isolation utilisateur

- [x] Applicable — vérifiée par construction : la sous-boucle lit par `executeTool(userId, workspace, …)`,
      la cible étant résolue par `(user_id, workspace_id)` du tour (aucun nouvel accès données, aucun
      nouveau dépôt). Les tests jouent un workspace possédé par `userId` (`requireOwned`).

---

## Dépendances

### Subfeatures bloquantes

- `SF-39-21` (explorations concurrentes) — **done**
- `SF-150-02` / `SF-121-13` (sous-tâche `task`) — **done**

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` inchangé).

---

## Notes et décisions (arbitrages tracés)

1. **Comment reconnaître une sous-tâche « en lecture » ?** → un booléen **explicite** `read_only` dans
   l'appel d'outil. *Alternative écartée* : deviner la nature en lecture à partir du texte du `prompt`
   (non déterministe ; une erreur de devinette autoriserait une écriture concurrente). **Réversible**
   (retirer le champ de la description ramène le comportement actuel).
2. **Un nouveau réglage `task-parallelism` ?** → **non** : le plafond existant
   `app.atelier.explore-parallelism` borne **toutes** les sous-boucles **en lecture** d'un tour.
   *Alternative écartée* : deux plafonds pour une même ressource (et un composant de plus dans le record
   `AtelierProperties`, dont la construction est un point sensible connu). **Réversible**.
3. **Worktree pour une sous-tâche en lecture ?** → **non** : rien n'écrit, il n'y a rien à isoler ; on
   évite la création/démontage git et l'exigence d'un dépôt. **Réversible**.
4. **Modèle et raisonnement de la sous-tâche en lecture** → **les mêmes que la sous-tâche écrivaine**
   (`app.atelier.task-model`, raisonnement du tour) : `read_only` ne change que la panoplie, le worktree
   et la concurrence. **Réversible**.
5. **Écoute de progression** : sous concurrence la sous-boucle utilise `AtelierProgressListener.NOOP`
   (comme `explore` depuis SF-39-21) ; la synthèse est relayée **après** jointure, sur le thread
   principal, dans l'ordre des appels.
6. **Provider Independence** : aucun appel direct à Anthropic — tout passe par `AiAgentProvider`, le
   modèle voyageant comme une chaîne. **Gateway-First** : on réutilise le patron de boucle existant,
   aucune capacité d'IA réimplémentée.
