# Cadrage — F-78 — Le registre des terminaux tient la concurrence

**Date** : 2026-09-12
**Source de vérité** : `docs/PRODUCT_SPEC.md`, ligne F-78 — les faits y sont **observés en
production le 2026-09-12**, pas supposés. Ce cadrage ne les rouvre pas.

---

## Le défaut, tel qu'il a été observé

Les journaux de production montrent, **en boucle** :

```
duplicate key value violates unique constraint "idx_live_terminals_user_session"
```

`LiveTerminalService.claim` **lit puis écrit** :

```
deleteStale(userId, cutoff)                 ← fenêtre 1 : une place expirée disparaît
findByUserIdAndSessionId(userId, sessionId) ← fenêtre 2 : deux appels ne trouvent RIEN
save(fresh)                                 ← les deux insèrent ; le second viole l'index
```

Entre la lecture et l'écriture, **rien ne protège**. Deux appels du **même onglet** arrivant
ensemble ne trouvent rien tous les deux et insèrent tous les deux.

**Pourquoi c'est devenu courant.** F-70 rendait la collision rare : un seul battement toutes les
30 s par onglet. F-76 a branché sur **le même appel** un envoi immédiat au changement d'activité et
un envoi apaisé à 5 s au défilement des lignes — **trois sources pour une seule ligne de base**.
Chaque collision rend **500** à un écran qui ne demandait qu'à tenir sa place.

---

## Ce qui ne doit pas bouger (contrat visible)

| Élément | Règle | Preuve conservée |
|---|---|---|
| Plafond | **quatre** places vivantes par utilisateur, borne dure non configurable | `LiveTerminalServiceTest.theCeilingCannotBeRaisedByConfiguration` |
| Refus | **409** `terminal_limit_reached` + la phrase du PO, mot pour mot | `theFifthTerminalIsRefusedWithTheSentenceTheScreenShows` |
| **Aucune trace d'un refus** | l'écran qui reçoit le 409 relit le registre et y compte **exactement quatre** | `aRefusedClaimLeavesNoTraceBehind`, `aRefusedFifthTerminalWritesNoPreviewEither` |
| Expiration | **90 s** sans battement → la place s'éteint d'elle-même | `aTabClosedBrutallyFreesItsPlaceOnItsOwn` |
| Aperçu F-76 | voyage sur **le même appel** ; un appel sans aperçu **n'efface rien** | `aHeartbeatWithoutAPreviewErasesNothing` |
| Isolation | `user_id` du jeton, `requireOwned` **avant** toute écriture | `aTerminalCannotBeOpenedOnSomeoneElsesProject` |

**Hors périmètre** (repris de `PRODUCT_SPEC.md`) : changer le plafond, la durée de vie d'une place,
ou la façon dont l'aperçu voyage.

---

## Ce qui existe déjà (constaté dans le code)

| Fait | Où | Conséquence |
|---|---|---|
| L'index unique `(user_id, session_id)` **existe** et fait son travail | migration `071-live-terminals.xml` | La base sait déjà refuser le doublon. Ce n'est pas elle qu'il faut changer : c'est l'écriture qui doit cesser de la surprendre. |
| Le plafond se détecte **après** insertion : on insère, on recompte, on retire sa ligne si l'on n'est pas dans les 4 plus anciennes | `LiveTerminalService.claim` | Ce chemin **reste** le bon : il est déjà « écrire puis arbitrer ». Il faut seulement qu'il ne s'applique qu'à une place **réellement créée**. |
| `LiveTerminalLimitReachedException` est une `RuntimeException` non capturée | `GlobalExceptionHandler` | La transaction **roule en arrière** au refus : la ligne insérée disparaît de toute façon. Le `delete` explicite est une ceinture, pas la bretelle. |
| `JdbcTemplate` est déjà utilisé pour du SQL natif, dans la même transaction que JPA | `PgVectorEmbeddingStore`, `PgNotifyRunnerRegistry` | Écrire une instruction native n'ouvre aucune pratique nouvelle dans ce dépôt. |
| Les tests d'intégration tournent sur **H2** (`application-test.yml`), la production sur **PostgreSQL 16** | `docker-compose.yml`, `application-test.yml` | Les deux moteurs n'écrivent **pas** l'upsert pareil : c'est l'arbitrage central de cette feature. |

---

## Ce que les deux moteurs savent faire (mesuré, pas supposé)

Quatre sondes JDBC ont été exécutées avant d'écrire la moindre ligne de production —
H2 2.3.232 (la version embarquée par Spring Boot 3.5.0) et PostgreSQL 16 réel.

| Écriture | H2 2.3.232 | PostgreSQL 16 |
|---|---|---|
| `INSERT … ON CONFLICT (…) DO UPDATE` | **Erreur de syntaxe** (aussi en `MODE=PostgreSQL`) | OK |
| `INSERT … ON CONFLICT DO NOTHING` | **Erreur de syntaxe** | OK |
| `MERGE INTO … KEY (…) VALUES` | OK — mais **écrase toute la ligne**, donc `opened_at` et l'aperçu | n/a |
| `MERGE INTO … USING … WHEN MATCHED` | OK **mais pas atomique** : 12 collisions sur 16 fils concurrents | n/a |
| `INSERT` nu, clef déjà prise par une transaction **non commitée** | **attend** la fin de l'autre transaction (mesuré : 1501 ms), puis `23505` — et **la transaction reste utilisable** | n/a (le `DO NOTHING` absorbe) |

Deux conclusions dures :

1. **H2 ne sait pas exprimer un upsert atomique.** Aucune syntaxe disponible ne combine
   « ne pas écraser `opened_at` / l'aperçu » et « ne jamais lever de doublon ».
2. **H2 sérialise correctement l'`INSERT` nu** : il attend le commit du jumeau avant de rendre
   `23505`, et — contrairement à PostgreSQL — **ne condamne pas** la transaction. Le repli
   « le jumeau a gagné, je renouvelle sa place » est donc exécutable sur H2 sans rien casser.

---

## Arbitrages

### A1 — La voie retenue : `ON CONFLICT DO NOTHING` + `UPDATE`, et non `DO UPDATE`

**Décision.** La prise de place devient :

```
1. UPDATE … WHERE user_id = ? AND session_id = ?      → 1 ligne = renouvellement, terminé
2. sinon INSERT … ON CONFLICT (user_id, session_id) DO NOTHING
        → 1 ligne insérée = place CRÉÉE (seul cas où le plafond s'arbitre)
        → 0 ligne = le jumeau a gagné la course → retour en 1
```

**Pourquoi pas `DO UPDATE`**, que la demande citait en premier : parce que le plafond a besoin de
savoir **si une place a été créée**. `DO UPDATE` rend « une ligne touchée » dans les deux cas et
efface la distinction ; il faudrait la reconstruire par `RETURNING (xmax = 0)` — un détail
d'implémentation PostgreSQL, illisible et intestable ailleurs. `DO NOTHING` rend exactement
l'information dont le plafond a besoin : **1 = créée, 0 = déjà prise**.

C'est toujours **un upsert natif** — le conflit est absorbé **par le moteur, dans l'instruction**,
jamais par un verrou applicatif ni par un rattrapage d'exception. Aucune des deux instructions n'a
de fenêtre : l'`UPDATE` est atomique, et le `DO NOTHING` attend le commit du jumeau avant de rendre
0.

**Écarté** : un verrou applicatif (ne tient pas sous HPA — plusieurs pods), un `try/catch` de
rattrapage sur PostgreSQL (la transaction est condamnée en `25P02`, le problème se déplace),
`MERGE … USING` (mesuré non atomique : 12 échecs sur 16).

**Réversible** : oui — deux instructions dans une classe de 60 lignes.

### A2 — Comment les deux moteurs sont couverts

**Décision.** L'instruction d'insertion est construite **selon le moteur**, dans une seule classe
(`LiveTerminalClaimWriter`) :

| Moteur | Instruction | Conflit |
|---|---|---|
| PostgreSQL (production) | `INSERT … ON CONFLICT (user_id, session_id) DO NOTHING` | rendu **0 ligne**, aucune exception, transaction intacte |
| H2 (tests) et tout autre | `INSERT` nu | `DuplicateKeyException` lue comme « 0 ligne » — H2 attend le commit du jumeau et **ne condamne pas** la transaction (mesuré) |

Le `catch` n'est **jamais** atteint en production : c'est la clause `DO NOTHING` qui absorbe. Il
n'existe que pour les moteurs sans `ON CONFLICT`, c'est-à-dire H2 dans les tests. Le service, lui,
ne voit qu'un `boolean` : **place créée, ou non**. Un seul chemin de décision, deux dialectes
d'écriture.

**Couverture** :
- **H2** — quatre tests d'intégration concurrents à travers l'**API**, dont
  `concurrentHeartbeatsOfTheSameTabNeverCollide` : 8 appels simultanés sur la même
  `(user, session)`, **tous 200**, une seule ligne. Ce test **échoue contre l'ancien code**, avec
  l'erreur exacte de production — c'est ce qui en fait une preuve.
- **PostgreSQL** — test JDBC dédié, joué contre le **vrai** PostgreSQL de `docker compose`, qui
  exécute **l'instruction de production** (lue sur la classe, pas recopiée) en 24 transactions
  concurrentes : zéro erreur, une seule création. Il **se saute de lui-même** quand aucun
  PostgreSQL n'écoute (CI, poste sans Docker), et ne peut donc pas rougir faussement.
  **Joué pour de vrai le 2026-09-12**, non sauté.
- **Forme du SQL** — test unitaire qui épingle, sans base, que le moteur `PostgreSQL` produit bien
  le suffixe `on conflict (user_id, session_id) do nothing` et que H2 ne le produit pas. C'est ce
  test qui empêche le dialecte de production de partir à la dérive sans que rien ne rougisse.

**Écarté** : Testcontainers — une dépendance de test de plus, une image PostgreSQL tirée à chaque
exécution de CI, pour une couverture que le PostgreSQL déjà documenté dans `CLAUDE.md`
(`docker compose up -d`) donne sans rien ajouter.

**Réversible** : oui.

### A3 — Le chemin du plafond, relu entièrement

**Décision.** L'arbitrage du plafond ne s'exécute **que** sur une place réellement **créée**.

C'était déjà vrai (l'ancien code ne recomptait que dans la branche `save(fresh)`), et la demande
avertissait justement qu'« un upsert qui insère d'abord change la façon dont le dépassement se
détecte ». La réponse est de **ne pas insérer d'abord** : l'`UPDATE` passe en premier, et un
renouvellement ne touche jamais au plafond — ce qui est la vérité métier : renouveler n'ajoute
aucune place.

Trois propriétés vérifiées ligne à ligne :

1. **Qui reste** — le recompte lit toujours les places vivantes triées par `opened_at` croissant, et
   la place créée ne survit que si elle figure parmi les `limit` plus anciennes. Inchangé.
2. **Aucune trace d'un refus** — la ligne créée est retirée explicitement **et** la transaction
   roule en arrière (l'exception est une `RuntimeException`). Deux garanties, comme avant.
3. **`opened_at` ne bouge jamais** — l'`UPDATE` de renouvellement ne le touche pas. C'est lui qui
   décide qui garde sa place en cas de course : un renouvellement qui le remettrait à l'instant
   présent ferait passer un ancien onglet pour un nouveau et changerait **qui** est refusé.

**Réversible** : oui.

### A4 — La course entre onglets DIFFÉRENTS n'est pas resserrée

**Décision.** F-78 corrige la course d'un onglet **avec lui-même**. La course entre **plusieurs
onglets** pour la dernière place garde le comportement de F-70 : on insère, puis on **recompte les
places commitées**. Sous `READ COMMITTED` — sur PostgreSQL comme sur H2 — deux onglets qui prennent
la quatrième et la cinquième place dans la même milliseconde peuvent chacun recompter sans voir
l'autre, et **cinq** places survivre à la photo-finish.

**Pourquoi ne pas le corriger ici.** Ce n'est pas le défaut observé le 2026-09-12 — celui-là est un
`duplicate key`, pas un dépassement — et le resserrer demanderait un verrou ou une isolation
sérialisable, c'est-à-dire **changer le comportement du plafond**, explicitement hors périmètre
(`PRODUCT_SPEC.md` : « changer le plafond » est hors scope). Un correctif qui élargit son propre
périmètre sous prétexte qu'il passait par là est un correctif qu'on ne sait plus relire.

Le test de course entre onglets vérifie donc ce qui est **réellement garanti** : aucun appel ne
reçoit de 500, et aucun onglet n'obtient deux lignes. Le compte exact de quatre reste tenu par les
tests séquentiels, qui décrivent le seul scénario que l'utilisateur vit vraiment (il ouvre ses
onglets l'un après l'autre).

**Réversible** : oui — mais c'est un **risque résiduel assumé**, et il est écrit.

### A5 — Pas de subfeature frontend

**Décision.** Aucune. Le contrat visible ne change pas — mêmes routes, mêmes corps, mêmes codes,
même phrase de refus. L'écran ne peut pas voir la différence, sinon qu'il cesse de recevoir des
**500**. La règle `CLAUDE.md` « subfeature backend mergée sans subfeature frontend planifiée » vise
une feature **qui a une UI nouvelle** ; ici, c'est un correctif de concurrence sous une UI livrée.

**Réversible** : oui.

---

## Découpage

| Subfeature | Objet | Taille |
|---|---|---|
| **SF-78-01** | La prise de place devient une écriture atomique | < 1 jour |

Une seule : le défaut est un seul chemin, et le découper donnerait deux moitiés dont aucune ne
corrige quoi que ce soit.

---

## Préoccupations transversales

| Préoccupation | Touchée ? | Analyse |
|---|---|---|
| Auth / Principal | **Non** | `currentUser.requireId()` inchangé, `requireOwned` toujours appelé **avant** toute écriture. |
| Contexte tenant | **Non** | `user_id` vient du jeton ; les deux nouvelles écritures le portent dans leur `WHERE` / leurs colonnes. Aucune méthode de lecture sans `user_id` n'est ajoutée. |
| Plans / limites | **Oui — le plafond de quatre** | Composants impactés : `LiveTerminalService.claim` (seul arbitre du plafond), `LiveTerminalLimitReachedException`, `GlobalExceptionHandler.handleLiveTerminalLimit` (inchangé), `LiveTerminalApiIntegrationTest` (4 tests de plafond conservés **tels quels** + 1 ajouté sur la course). Voir A3. |
| Navigation / routing | **Non** | Aucune route ajoutée, retirée ni déplacée. |
