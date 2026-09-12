# Mini-spec — F-78 / SF-78-01 — Prendre une place en une seule écriture

---

## Identifiant

`F-78 / SF-78-01`

## Feature parente

`F-78` — Le registre des terminaux tient la concurrence

## Statut

`done`

## Date de création

2026-09-12

## Branche Git

`feat/SF-78-01-place-atomique`

---

## Objectif

Faire de la prise (ou du renouvellement) d'une place de terminal vivant **une écriture atomique sur
`(user_id, session_id)`**, pour que deux battements du même onglet arrivant ensemble ne violent plus
jamais `idx_live_terminals_user_session` — **sans rien changer** au contrat visible.

---

## Comportement attendu

### Cas nominal

`POST /api/workspaces/{id}/terminal/live` — corps et réponse **inchangés**.

```
1. requireOwned(userId, workspaceId)      ← isolation d'abord, comme avant
2. deleteStale(userId, now - 90 s)        ← purge des places éteintes, comme avant
3. UPDATE live_terminals
      SET workspace_id = ?, last_seen_at = ?  [, activity, activity_detail,
                                                 preview_lines, activity_at si aperçu]
    WHERE user_id = ? AND session_id = ?
   → 1 ligne  : RENOUVELLEMENT. Le plafond n'est pas arbitré (renouveler n'ajoute aucune place).
4. → 0 ligne  : INSERT … ON CONFLICT (user_id, session_id) DO NOTHING   (PostgreSQL)
                INSERT nu, le doublon lu comme « déjà prise »           (H2 et autres)
   → 1 ligne insérée : place CRÉÉE → arbitrage du plafond (étape 5)
   → 0 ligne          : un jumeau du même onglet a gagné la course → retour à l'étape 3
5. Plafond (uniquement sur une place CRÉÉE) : recompte des places vivantes triées par
   `opened_at` croissant. Si la place créée ne figure pas parmi les `limit` plus anciennes :
   sa ligne est retirée, et `LiveTerminalLimitReachedException` est levée (la transaction
   roule en arrière : **aucune trace**).
```

Les étapes 3 et 4 forment une boucle bornée à **3 tours**. Dans les faits, un seul suffit : sur
PostgreSQL le `DO NOTHING` attend le commit du jumeau avant de rendre 0, sur H2 l'`INSERT` nu attend
de même avant de rendre `23505` (mesuré). Si les trois tours échouaient — le jumeau ayant à chaque
fois été refusé puis annulé entre-temps — l'appel rend **le registre tel quel**, sans erreur : le
battement suivant, quelques secondes plus tard, reprendra une place. Un écran qui perd un battement
se rattrape ; un écran qui reçoit un 500 affiche une panne.

### Ce qui ne change pas — vérifié un par un

| Contrat | Inchangé parce que |
|---|---|
| Plafond **quatre**, borne dure | `limit` et `MAX_LIMIT` ne sont pas touchés ; l'arbitrage est le même recompte trié par `opened_at`. |
| **409** + phrase du PO | L'exception et `GlobalExceptionHandler` ne sont pas touchés. |
| **Aucune trace d'un refus** | La ligne créée est retirée explicitement **et** la transaction roule en arrière. |
| Expiration **90 s** | `ttl`, `deleteStale` et le `cutoff` de lecture sont inchangés. |
| Aperçu **F-76** sur le même appel | Les colonnes d'aperçu sont écrites par la **même** instruction que la place — une seule écriture, comme avant. |
| Un appel **sans aperçu n'efface rien** | Deux `UPDATE` distincts : celui sans aperçu ne mentionne pas les colonnes d'aperçu (plutôt qu'un `CASE` qui écrirait la valeur existante par-dessus elle-même). |
| Un onglet qui **change de projet garde sa place** | `workspace_id` est écrit par l'`UPDATE` de renouvellement ; `opened_at` n'est jamais réécrit. |
| Isolation `user_id` | Les deux nouvelles écritures portent `user_id` dans leur `WHERE` / leurs colonnes. Aucune lecture ni écriture par le seul `session_id` n'est introduite. |

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| `sessionId` absent, vide ou non conforme | Message d'erreur explicite (validation, inchangée) | 400 |
| Projet inexistant **ou appartenant à un autre** | Inexistant — rien n'est écrit (inchangé) | 404 |
| Cinquième place demandée | `terminal_limit_reached` + la phrase du PO, **aucune ligne laissée** | 409 |
| **Deux appels concurrents du même onglet** | Les deux réussissent : un crée, l'autre renouvelle. **Plus jamais** de `duplicate key` | 200 / 200 |
| Appels concurrents de **plusieurs onglets** sur la dernière place | Chacun reçoit sa place ou le refus du PO, **jamais** une panne (le compte exact sous photo-finish : voir arbitrage A4 du cadrage) | 200 / 409 |
| Sans jeton | Refus (inchangé) | 401 |

---

## Critères d'acceptation

1. `LiveTerminalService.claim` ne contient plus **aucune** séquence « lire, puis décider d'écrire »
   sur `(user_id, session_id)` : plus d'appel à `findByUserIdAndSessionId` dans ce chemin.
2. Huit appels **concurrents** sur la **même** `(user, session)`, à travers l'API : **tous** rendent
   **200**, une **seule** ligne existe en base, et son `opened_at` est celui du premier.
3. Huit appels **concurrents** sur **huit onglets différents** : chacun rend **200 ou 409**, jamais
   **500**, et aucun onglet n'a deux lignes.
4. Sur PostgreSQL, l'instruction d'insertion **se termine** par
   `on conflict (user_id, session_id) do nothing` ; sur H2, elle ne porte aucun suffixe. Et cette
   instruction-là, jouée contre un **vrai** PostgreSQL en 24 transactions concurrentes, rend
   **une seule** création et **zéro** erreur.
5. Les **vingt-six** tests d'intégration existants de `LiveTerminalApiIntegrationTest` passent
   **sans être modifiés** — c'est la preuve que le contrat visible n'a pas bougé.
6. Aucune migration : le schéma ne change pas.

---

## Plan de test minimal

### Unitaires

| Test | Vérifie |
|---|---|
| `LiveTerminalClaimWriterSqlTest.postgresAbsorbsTheConflictInTheStatement` | Le SQL PostgreSQL porte `on conflict (user_id, session_id) do nothing`. |
| `…thePostgresProductNameIsRecognisedHoweverItIsWritten` | `PostgreSQL 16.4 (Debian)` est reconnu comme PostgreSQL. |
| `…otherEnginesInsertPlainly` | Le SQL H2 — et celui d'un moteur non identifié — ne porte aucun suffixe. |
| `…theStatementWritesThePlaceAndItsPreviewAtOnce` | Les dix colonnes sont écrites par la **même** instruction (l'aperçu voyage avec la place). |
| `…theConflictTargetIsNamedRatherThanLeftToTheEngine` | La cible du conflit est nommée : une collision de clef primaire resterait une anomalie visible. |
| `LiveTerminalServiceTest` (existants, 6) | Le plafond dur et le bornage du TTL, intacts. |

### Intégration (H2, profil `test`)

| Test | Vérifie |
|---|---|
| `concurrentHeartbeatsOfTheSameTabNeverCollide` **(nouveau)** | 8 appels simultanés sur la même `(user, session)`, moitié avec aperçu : **tous 200**, une seule ligne. |
| `concurrentHeartbeatsKeepTheOldestOpenedAt` **(nouveau)** | 8 renouvellements simultanés ne réécrivent pas `opened_at` — l'ordre de refus reste celui de l'ouverture. |
| `concurrentTabsRacingForTheLastPlacesNeverGetAnError` **(nouveau)** | 8 onglets différents simultanés : **200 ou 409**, jamais 500 ; aucun onglet en double. |
| `aTabRefusedWhileBeatingLeavesTheRegisterAtExactlyFour` **(nouveau)** | Le cinquième onglet **bat trois fois** : trois fois le même 409, et le registre reste à quatre. |
| `aRefusedClaimLeavesNoTraceBehind` (existant, non modifié) | Le registre relu compte **exactement quatre**. |
| `aHeartbeatWithoutAPreviewErasesNothing` (existant, non modifié) | Un battement sans aperçu n'efface rien. |
| `aTabThatChangesProjectKeepsItsPlace` (existant, non modifié) | Le changement de projet ne consomme pas une place. |
| `aTabClosedBrutallyFreesItsPlaceOnItsOwn` (existant, non modifié) | L'expiration à 90 s. |

### Intégration (PostgreSQL réel, `docker compose up -d`)

| Test | Vérifie |
|---|---|
| `LiveTerminalClaimWriterPostgresTest.theProductionStatementSurvivesConcurrentClaimsOfTheSameTab` | **L'instruction de production**, lue sur la classe et non recopiée, exécutée en **24 transactions concurrentes** contre PostgreSQL 16 : zéro erreur, **une seule** création, une ligne — et au moins un « conflit puis renouvellement », preuve que le conflit a bien eu lieu et a bien été absorbé. **Se saute** si aucun PostgreSQL n'écoute sur `localhost:5432`. |

> **Exécuté pour de vrai le 2026-09-12** contre PostgreSQL 16 (`docker compose up -d`) :
> `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`. Sans PostgreSQL, il se serait déclaré *skipped*
> — l'écart entre les deux est visible dans la sortie de la suite.

### Ce que le nouveau test prouve — vérifié contre l'ancien code

Avant de conclure, `concurrentHeartbeatsOfTheSameTabNeverCollide` a été joué **contre la version
d'avant le correctif** (`LiveTerminalService` restauré depuis `origin/main`). Il échoue, et il
échoue exactement de la panne de production :

```
7 réponses sur 8 en 500
org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException: Unique index or primary key violation:
  "PUBLIC.IDX_LIVE_TERMINALS_USER_SESSION ON PUBLIC.LIVE_TERMINALS(USER_ID, SESSION_ID)"
```

Un test de concurrence qui passerait aussi bien avant qu'après ne prouverait rien.

---

### Isolation utilisateur

Les quatre tests d'isolation existants (`aTerminalCannotBeOpenedOnSomeoneElsesProject`,
`theCeilingOfOneUserNeverBlocksAnother`, `theRegisterNeverNamesSomeoneElsesProject`,
`oneTabCannotFreeAnotherUsersPlace`, `anotherUsersPreviewNeverReachesMyScreens`) sont conservés
**intacts** et doivent passer.

---

## Tables / endpoints / composants impactés

### Tables

**Aucune migration.** `live_terminals` est inchangée — c'est son index unique existant
`idx_live_terminals_user_session` qui devient l'arbitre au lieu d'être la victime.

### Endpoints

Aucun ajout, aucun retrait, aucun changement de corps ni de code :
`POST /api/workspaces/{id}/terminal/live`, `DELETE /api/workspaces/{id}/terminal/live`,
`GET /api/terminals/live`.

### Composants backend

| Fichier | Nature |
|---|---|
| `terminals/LiveTerminalClaimWriter.java` | **Nouveau** — l'insertion atomique, et le seul endroit du dépôt qui connaisse un dialecte. |
| `terminals/LiveTerminalRepository.java` | `renew` / `renewWithPreview` (JPQL, portables) ; `findByUserIdAndSessionId` conservé (utilisé par les tests et par aucun chemin de décision). |
| `terminals/LiveTerminalService.java` | `claim` réécrit : renouveler, sinon prendre, sinon renouveler. |

### Frontend

**Aucun.** Le contrat visible ne bouge pas (voir arbitrage A4 du cadrage).

---

## Hors périmètre

- Changer le **plafond**, la **durée de vie** d'une place, ou la façon dont **l'aperçu voyage**
  (hors périmètre posé par `PRODUCT_SPEC.md`).
- Réduire le nombre de battements côté écran (c'est F-76 qui les émet ; les espacer serait une
  autre décision, produit).
- Remplacer `deleteStale` par une purge globale planifiée.
- Ajouter un verrou applicatif ou une file : ce serait remplacer une garantie de la base par une
  garantie du pod, fausse sous HPA.
