# Mini-spec — F-151 / SF-151-02 — L'agent inscrit le blocage

## Identifiant
`F-151 / SF-151-02` — feature parente `F-151` — dépend de **SF-151-01** (le modèle)

## Objectif
Que l'agent **inscrive lui-même** l'action à faire au moment exact où il bute sur une dépendance
humaine — au lieu de l'écrire dans une réponse que personne ne relira.

## Le défaut
> PO : *« Je commence un sujet, bim, je suis coincé. Il est question de contacter telle personne. »*

SF-151-01 a donné la table. Sans cette subfeature, il faudrait l'alimenter **à la main** — c'est-à-dire
jamais, puisque c'est précisément le moment où l'on passe à autre chose.

## Comportement attendu
1. Un outil **`record_blocker`** est donné à l'agent dans un terminal de projet, sous **la même garde
   d'espace** que les autres outils de la gateway (Forge / Vigie ; l'ADMIN l'a d'office).
2. L'agent l'appelle quand — et **seulement** quand — il bute sur quelque chose que **l'utilisateur
   seul** peut débloquer : contacter quelqu'un, obtenir un accès, une validation, une information
   qui n'est nulle part.
3. L'appel porte : **ce qu'il faut faire**, **ce que ça débloque**, **qui** est concerné, la
   **nature** du geste (un message à envoyer, ou un geste à faire), et une **clé courte** qui
   identifie le blocage.
4. **Deux fois le même blocage ne font pas deux actions.** La clé dédoublonne, **quel que soit
   l'état** de l'action existante — y compris **annulée** : l'utilisateur a dit non, l'agent ne
   redemande pas.
5. Le résultat rendu à l'agent dit **ce qui s'est passé** : inscrite, déjà inscrite, ou annulée par
   l'utilisateur — pour qu'il en tienne compte dans sa réponse plutôt que de redemander.
6. L'agent **continue son tour** : une action inscrite n'interrompt rien. Elle est un **post-it**,
   pas une barrière.

| Cas d'erreur | Comportement |
|---|---|
| Énoncé vide, ou borne dépassée | **résultat d'outil en erreur**, nommé — le tour continue |
| Liste du terminal saturée (50 ouvertes) | résultat en erreur qui le dit — on ne fait pas grossir un tas |
| Outil appelé sans le droit d'espace | l'outil **n'est pas donné** ; s'il est quand même appelé, refus nommé |
| Base indisponible | résultat en erreur ; **jamais** une exception qui tue le tour |

## Critères d'acceptation
- [ ] L'outil et son guide n'apparaissent **que** sous le droit d'espace du terminal.
- [ ] Un appel nominal crée une action **ouverte**, rattachée au projet du tour.
- [ ] Le **même** blocage rappelé deux fois dans le même projet ne crée **qu'une** action.
- [ ] Un blocage dont l'action a été **annulée** par l'utilisateur **n'est pas recréé**, et l'agent
      l'apprend par le résultat de l'outil.
- [ ] Toute erreur est un **résultat d'outil**, jamais une exception : le tour se termine.
- [ ] **ISOLATION** : l'action est écrite pour le `user_id` **du tour** et le projet **du tour** —
      jamais un identifiant venu des paramètres de l'outil.
- [ ] Le guide **n'entre dans la consigne que sous la garde**, comme tous les autres (cache F-134).

## Hors scope
Le **menu** (**SF-151-03**) · la **fermeture par la conversation** (**SF-151-04**) · l'envoi du
message depuis la liste · toute relance automatique.

## Technique
| Élément | Changement |
|---|---|
| Migration `131-terminal-actions-dedup-key.xml` | colonne `dedup_key` + index **unique** `(user_id, workspace_id, dedup_key)` |
| `TerminalActionToolCatalog` | l'outil `record_blocker`, sa garde d'espace, son guide |
| `TerminalActionToolExecutor` | l'exécution, et les trois issues (inscrite / déjà là / annulée) |
| `TerminalActionService.record(...)` | l'inscription dédoublonnée |
| `AtelierChatService` | l'outil dans le catalogue du tour, le guide **sous garde**, l'aiguillage |

**La clé** : l'agent en donne une courte (`acces-vpn-karim`). Absente, elle est **dérivée** de
l'énoncé normalisé — jamais laissée vide, sinon le dédoublonnage ne protège rien.

## Plan de test
- [ ] Outil absent sans le droit d'espace, présent avec ; guide idem.
- [ ] Appel nominal → action ouverte, projet du tour, `user_id` du tour.
- [ ] Deuxième appel, même clé → **une seule** action, résultat « déjà inscrite ».
- [ ] Action **annulée** puis même clé → **pas de recréation**, résultat qui le dit.
- [ ] Énoncé vide / borne dépassée / liste saturée → **résultat en erreur**, le tour continue.
- [ ] **ISOLATION** : le projet et le compte écrits sont ceux **du tour**, même si l'appel en nomme
      d'autres dans ses paramètres.

## Préoccupations transversales
| Préoccupation | Cochée | Composants impactés |
|---|---|---|
| Auth / Principal | non | inchangé — le tour porte déjà son `user_id` |
| **Contexte tenant** | **oui** | `TerminalActionToolExecutor` ne lit **aucun** identifiant de compte ni de projet dans les paramètres de l'outil : il reçoit le `userId` et le `Workspace` **déjà vérifiés** du tour (`requireOwned` en amont dans `AtelierChatService`). Même patron que `PresentationToolExecutor`, `DiagramToolExecutor`, `ImageToolExecutor`. |
| **Plans / limites** | **oui** | nouvelle garde d'espace : `SpaceEntitlementService.isEntitled(userId, FORGE\|VIGIE)`, la même que `PageToolCatalog`, `PresentationToolCatalog`, `ImageToolCatalog`, `DiagramToolCatalog`, `DeckToolCatalog`. Abonnement illisible → **fermé**. |
| Navigation / routing | non | aucune route |
