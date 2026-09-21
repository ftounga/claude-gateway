# Mini-spec — F-141 / SF-141-04 Reclasser une entrée

## Identifiant
`F-141 / SF-141-04`

## Feature parente
`F-141` — L'aiguilleur de sujet à la racine

## Statut
`in-progress`

## Date de création
2026-09-22

## Branche Git
`feat/SF-141-04-reclasser`

---

## Objectif
Déplacer un fait durable **mal rangé** d'une carte vers une autre (sujet↔sujet, racine↔projet) **en un seul geste**, avec **trace**, sans jamais perdre le fait, en réutilisant l'écriture des cartes existante.

---

## Comportement attendu

### Cas nominal
1. Au **terminal du poste**, l'agent appelle `reclass_entry{from, to, entry}` (chemins relatifs à la racine ; `entry` = texte exact de la ligne).
2. La gateway **lit la source** (`GovernanceHostFiles.read`) et vérifie que la ligne exacte y est.
3. Elle **ajoute d'abord** la ligne à la destination (avec une trace « reclassé depuis `from` le AAAA-MM-JJ »), puis **retire** la ligne de la source. Cet **ordre** garantit qu'un échec ne perd jamais le fait (au pire, doublon récupérable).
4. Elle rend une **confirmation** (trace visible) ; le journal d'audit des écritures (`governance_map_write`) porte la trace durable.

### Cas d'erreur / limites
| Situation | Comportement attendu |
|-----------|----------------------|
| `from` == `to` | Refus immédiat, aucune lecture/écriture |
| `from`/`to`/`entry` vide | Refus avec action corrective |
| Source illisible/injoignable (≠ PRESENT) | Refus, rien n'est déplacé |
| Ligne exacte absente de la source | Refus (« copie le texte exact »), rien n'est déplacé |
| Destination illisible (ni PRESENT ni ABSENT) | Refus, rien n'est déplacé |
| Écriture destination échoue | Refus, **source intacte** (fait non perdu) |
| Écriture source échoue après ajout destination | Message clair : fait ajouté à `to`, à retirer à la main de `from` (doublon, pas de perte) |
| Appel hors terminal du poste, ou outil non câblé | Refus : l'outil n'existe qu'à la racine |

---

## Critères d'acceptation
- L'outil `reclass_entry` est **offert uniquement au terminal du poste** et **seulement si** l'écriture des cartes est câblée (`GovernanceHostFiles`).
- Un reclassement valide **déplace la ligne** de `from` vers `to`, avec **trace** de provenance, en réutilisant `GovernanceHostFiles.read/write` (outils runner existants).
- **Aucune perte** : la destination est écrite **avant** le retrait de la source ; un échec d'écriture destination laisse la source **intacte**.
- **Ligne exacte introuvable** ⇒ refus sans rien écrire ; **from==to** ⇒ refus sans rien lire.
- **Isolation** `user_id`+`host_id` : la carte visée est celle du poste possédé du terminal.
- **Non-régression** : l'écriture des cartes existante (`GovernanceHostFiles`) inchangée ; F-125, capture réunion, Radar, Vigie, terminaux par sujet inchangés.

---

## Plan de test minimal
- `reclassEntryToolIsOfferedOnlyOnTheHostTerminalWhenWired` / `…IsAbsentWhenNotWired` / `…IsAbsentOnAnOrdinaryProject`.
- `reclassEntryMovesTheLineWithTraceFromSourceToTarget` : destination reçoit la ligne + trace ; source ne la contient plus (garde les autres).
- `reclassEntryDoesNotLoseTheFactWhenTargetWriteFails` : écriture destination échoue ⇒ source jamais réécrite.
- `reclassEntryRejectsWhenTheLineIsNotInTheSource` : aucune écriture.
- `reclassEntryRejectsIdenticalSourceAndTarget` : aucune lecture ni écriture.
- **Isolation** : les lectures/écritures portent `user_id` (appelant) et la `GovernanceHostRef` du `host_id` possédé (vérifié par `eq(userId)` dans les tests).

---

## Tables / endpoints / composants impactés
- `AtelierChatService` : outil `reclass_entry` (déclaré si `isHostTerminal()` et `governanceHostFiles != null`), branche de dispatch dans `executeTool`, méthode `executeReclassEntry`, dépendance optionnelle `GovernanceHostFiles` injectée par mutateur (`setGovernanceHostFiles`).
- Réutilisé sans modification : `GovernanceHostFiles.read/write` (`governance_map_read`/`governance_map_write`), `GovernanceHostRef.of`.
- **Aucune** table, **aucun** endpoint HTTP, **aucune** migration.

## Préoccupations transversales
- **Contexte tenant** : la carte visée est résolue par le terminal possédé (`workspace` déjà `requireOwned`) → `user_id` de l'appelant + `GovernanceHostRef.of(workspace.getHostId())`. Seul ajout de résolution tenant : `executeReclassEntry` → `GovernanceHostFiles` (déjà isolé par `host_id` via `rootTarget`). Aucun autre chemin touché.
- **Auth / Principal** : inchangé.
- **Navigation / routing** : aucune route front.

## Mise à jour runner
**Non.** `reclass_entry` est un **outil traité par la gateway** ; il lit/écrit via `GovernanceHostFiles`, qui utilise les outils runner **existants** (`governance_map_read`/`governance_map_write`). Aucun nouvel outil runner, aucune mise à jour du binaire runner.

## Décision (documentée)
Pas d'**annulation automatique** : aucune infrastructure d'annulation n'existe pour l'écriture des cartes. La SF livre donc le **geste simple + trace** (audit `governance_map_write` + confirmation), conformément au cadrage §4.4 (« annulable si l'infrastructure d'annulation existe »).

## Hors périmètre
- L'annonce (SF-141-01), l'aiguillage (SF-141-02), la création de sujet (SF-141-03) — déjà livrés.
