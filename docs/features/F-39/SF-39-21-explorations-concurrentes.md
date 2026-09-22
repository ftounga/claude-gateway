# Mini-spec — F-39 / SF-39-21 Moteur : explorations concurrentes

## Identifiant

`F-39 / SF-39-21`

## Feature parente

`F-39` — L'Atelier comme harnais (sous-boucle d'exploration, SF-39-14)

## Statut

`ready`

## Date de création

2026-09-22

## Branche Git

`feat/SF-39-21-explorations-concurrentes`

---

## Objectif

> En une phrase : quand un tour assistant émet **plusieurs `explore` indépendants**, les exécuter
> **en parallèle** via un pool borné, au lieu de l'un après l'autre — sans rien changer d'autre
> (lecture seule, bornes, coût imputé au tour).

---

## Comportement attendu

### Cas nominal

Extension de SF-39-14. Aujourd'hui la boucle principale (`AtelierChatService`, ~1608) itère
`turn.toolCalls()` **en série** ; chaque `explore` bloque la sous-boucle suivante. Trois explorations
de 20 s = 60 s de mur.

Désormais :
1. Avant d'exécuter les outils du tour, la boucle **isole les appels `explore`** du tour, dans la
   limite du plafond par message (`maxDelegations`).
2. Elle les exécute **ensemble** via un **pool borné** de taille `app.atelier.explore-parallelism`
   (défaut **3**). Au-delà de la borne, les explorations sont servies **par vagues**.
3. Les **autres outils** du tour restent **séquentiels et dans l'ordre** — l'ordre des écritures ne
   change pas (hors périmètre : le parallélisme entre outils non-`explore`).
4. Chaque conclusion est rattachée à l'appel dont elle vient (corrélation par appel) ; les
   `tool_result` sont rendus **dans l'ordre des appels**, quel que soit l'ordre de fin.
5. Le **coût** de toutes les sous-boucles (input / output / cacheRead / cacheWrite) est **additionné
   dans les compteurs du tour**, exact sous concurrence, et le `AtelierProgressListener` est mis à
   jour de façon thread-safe. L'agrégation se fait **sur le thread principal** après la jointure du
   pool, dans l'ordre des appels — jamais depuis un thread ouvrier.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Une exploration échoue (timeout, tronquée, erreur provider) | Elle rend **son** erreur comme **son** `tool_result`, sans tuer les autres (isolation des échecs, D5). |
| Nombre d'`explore` dans le tour > `maxDelegations` | Les premiers (dans l'ordre d'appel) s'exécutent ; les suivants reçoivent le message « Limite de délégations atteinte pour ce message » — comportement identique à SF-39-14. |
| Question vide | `tool_result` en erreur « Question requise pour explorer. » (inchangé). |
| Interruption / budget de temps du tour dépassé | Le `BooleanSupplier stop` et la `deadline` du tour sont **partagés** par toutes les sous-boucles (D7). |

---

## Critères d'acceptation

- [ ] Deux `explore` émis dans un même tour s'exécutent **concurremment** (démontré par test :
      recouvrement temporel via une barrière ; l'exécution sérielle ferait échouer le test).
- [ ] Les `tool_result` reviennent **dans l'ordre des appels**, même quand une sous-boucle finit avant
      une autre.
- [ ] Le **coût total** (toutes sous-boucles + boucle principale) est **imputé au tour** ; l'agrégation
      est exacte sous concurrence.
- [ ] Le **plafond par message** (`maxDelegations`) est respecté : un `explore` au-delà du plafond
      reçoit l'erreur de limite, sans casser le tour.
- [ ] Le **plafond de parallélisme** est respecté : avec N > plafond, la concurrence observée ne
      dépasse jamais le plafond (vagues).
- [ ] Un **échec** d'une exploration n'affecte pas les autres.
- [ ] La **lecture seule** est **inviolée** : la sous-boucle ne reçoit toujours que des outils de
      lecture (aucune écriture, aucune commande).
- [ ] Nouveau réglage `app.atelier.explore-parallelism` (défaut 3) ajouté **additivement** à
      `AtelierProperties` (constructeur de compatibilité préservé — aucun appelant/test cassé).
- [ ] Isolation `user_id` / cible d'exécution **inchangée**.

---

## Périmètre

### Hors scope (explicite)

- Tout sous-agent qui **écrit** ou **exécute** (bash) — reste interdit en sous-boucle (D2 inchangé).
- Le **fan-out multi-agents généralisé** / Managed Agents.
- Le parallélisme **entre outils non-`explore`** d'un tour (l'ordre des écritures ne change pas).
- Toute modification du **protocole runner** (voir §Constatation runner).

---

## Technique

### Réglage ajouté

`AtelierProperties.exploreParallelism` (23ᵉ composant, ajouté après `perMessageEffort`) :
- Défaut **3** (`DEFAULT_EXPLORE_PARALLELISM`).
- Repli : valeur absente / nulle / < 1 → défaut ; valeur déraisonnable ramenée à un plafond lisible
  (`MAX_EXPLORE_PARALLELISM = 16`).
- **Constructeur de compatibilité** : ajout d'un constructeur reprenant la forme F-134 (22 composants,
  jusqu'à `perMessageEffort`) qui laisse `exploreParallelism` retomber sur son défaut ; les
  constructeurs de compat existants reçoivent un `null` supplémentaire. Aucun appelant existant cassé.

### Composants impactés

| Composant | Opération | Notes |
|-----------|-----------|-------|
| `AtelierChatService` (boucle tool-use) | modif | Pré-exécution concurrente des `explore` du tour via pool borné ; agrégation coût + `onProgress` sur le thread principal, dans l'ordre des appels. |
| `AtelierProperties` | modif additive | Nouveau réglage `explore-parallelism` + constante + constructeur de compat. |
| `AtelierExploration` | **inchangé** | Appelé en parallèle, sa logique ne bouge pas (D2/D4). |
| Frontière runner (`RunnerToolGateway`/`RunnerCallDispatcher`) | **inchangé** | Voir §Constatation runner. |

### Migration Liquibase

- [x] Non applicable (aucune table, aucun schéma).

### Composants Angular

- Aucun — c'est du moteur (backend pur, aucun écran).

---

## Constatation runner (DRAPEAU du cadrage §4)

**Lecture du code faite** (`RunnerCallDispatcher`, `RunnerToolGateway`, `RunnerCallRouter`).

Le canal runner de la gateway est **entièrement multiplexé par `callId`** :
- `inFlight` est une `ConcurrentHashMap<String, InFlightCall>` ; chaque appel bloque sur **sa propre**
  `CompletableFuture` ;
- les écritures socket passent par un `ConcurrentWebSocketSessionDecorator` (sérialisées, thread-safe) ;
- les résultats entrants sont rattachés par l'`id` de la trame ;
- le dispatcher anticipe **déjà** plusieurs appels en vol par workspace (`cancelWorkspace`/`cancelHost`
  itèrent les appels en vol).

Les outils de lecture (`read_file`/`list_files`/`search_files`/`grep`/`glob`) sont **sans état
partagé** et **idempotents** (chaque appel porte son chemin ; pas de curseur partagé ; `bash` interdit
en exploration). Chaque sous-boucle parallèle utilise un `callId` distinct.

**Voie choisie : parallélisme complet des sous-boucles, SANS verrou de frontière runner et SANS
changement de protocole.** C'est la voie sûre : le dispatcher est bâti pour des appels concurrents
corrélés par `callId`. Si un binaire runner donné sérialisait ses opérations fichier en interne, la
correction resterait intacte (les lectures y feraient la file pendant que la pensée du modèle se
recouvre). **Mise à jour runner : NON.**

---

## Plan de test

### Tests unitaires

- [ ] `AtelierPropertiesTest` — `explore-parallelism` : défaut 3 ; repli sur non-positif ; plafond
      lisible ; constructeur de compat (forme F-134) applique le défaut.
- [ ] `AtelierChatServiceParallelExploreTest` — deux `explore` d'un tour s'exécutent **concurremment**
      (barrière : sérialisé ⇒ timeout ⇒ échec).
- [ ] même test — **ordre des `tool_result`** conforme à l'ordre des appels même quand la seconde
      exploration finit avant la première.
- [ ] même test — **coût total** (2 sous-boucles + boucle) imputé au tour, exact.
- [ ] même test — **plafond par message** : un 4ᵉ `explore` (maxDelegations=3) reçoit l'erreur de
      limite, le tour aboutit.
- [ ] même test — **plafond de parallélisme** : avec `explore-parallelism=1` et 2 `explore`, la
      concurrence observée reste 1 (vagues) ; les deux résultats reviennent dans l'ordre.
- [ ] même test — **isolation des échecs** : une exploration qui lève rend son erreur, l'autre réussit.
- [ ] Non-régression : tests existants de `AtelierExploration`/`AtelierChatService` (explore) verts,
      `AtelierPropertiesTest` vert.

### Tests d'intégration

- Sans objet (aucun endpoint HTTP nouveau ; comportement moteur, couvert par tests de service).

### Isolation workspace

- [x] Applicable — chaque sous-boucle reçoit explicitement `userId` + `workspace` (aucun état de
      sécurité implicite / `ThreadLocal`). Vérifié : la voie de lecture passe `userId` en paramètre.

---

## Préoccupations transversales

| Préoccupation | Impact | Composants |
|--------------|--------|-----------|
| **Plans / limites** | Nouveau réglage de parallélisme ; le plafond par message (`maxDelegations`) reste la vérité de coût ; compteurs exacts sous concurrence. | `AtelierChatService` (agrégation coût, `onProgress`), `AtelierProperties` (réglage). |
| **Concurrence** | Compteurs de tokens + `AtelierProgressListener` + agrégation des résultats sous accès concurrent. | `AtelierChatService` : agrégation faite sur le thread principal après jointure (jamais depuis un ouvrier) → thread-safe par construction. |
| **Exécution / runner** | Frontière de lecture vers le runner. | `RunnerCallDispatcher` (déjà multiplexé par `callId`) — **inchangé** (voir §Constatation runner). |
| **Auth / Principal** | Aucun changement d'auth ni de Principal. | — |
| **Contexte tenant** | Aucun nouveau moyen de résoudre le tenant ; `userId`/cible passés explicitement. | — |
| **Navigation / routing** | Aucune route. | — |

---

## Dépendances

### Subfeatures bloquantes

- `SF-39-14` — sous-boucle d'exploration — statut : done.
- `SF-39-20` — panoplie de lecture de l'exploration — statut : done.

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non concerné).

---

## Notes et décisions

- **D-39-21-1** : l'agrégation du coût se fait sur le **thread principal** après jointure du pool, dans
  l'ordre des appels. C'est la forme la plus sûre pour D4 : aucun compteur n'est muté depuis un thread
  ouvrier, donc exactitude garantie sans dépendre d'accumulateurs atomiques.
- **D-39-21-2** : le pré-calcul des résultats d'exploration est indexé par **identité d'appel**
  (`IdentityHashMap`) et non par `callId`, parce que `correlationId(call)` fabrique un UUID pour un id
  vide et donnerait deux valeurs différentes à deux invocations.
- **D-39-21-3** : un pool `newFixedThreadPool(min(parallelism, N))` fournit les **vagues** par
  construction (au plus `parallelism` sous-boucles en vol) ; `shutdownNow()` en `finally`.
- **D-39-21-4** : voie runner sûre sans changement de protocole (voir §Constatation runner).
