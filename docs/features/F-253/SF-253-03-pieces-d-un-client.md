# Mini-spec — F-253 / SF-253-03 Les pièces d'un client et les noms dits

## Identifiant
`F-253 / SF-253-03`

## Feature parente
`F-253` — Jarvis montre vraiment (cadrage §3 #2 et #3)

## Statut
`in-review` · **Estimation :** 2 j · **Branche :** `feat/SF-253-03-pieces-d-un-client` · **Dépend de :** SF-253-02 (Done)

## Objectif
« Les vidéos chez CAGIP » s'ouvre sans sujet choisi : les pièces sont comptées pour tous les sujets du compte, `pieces`
et `afficher` acceptent un client comme portée, et les noms prononcés sont rapprochés côté serveur.

## Comportement attendu

### Cas nominal
1. Le **message** (jamais la consigne : le préfixe reste stable pour le cache) porte un bloc
   `## Pièces de tous les sujets` : une ligne « client / sujet : comptes par type », les sujets les plus fournis d'abord,
   ≤ 1 400 caractères, sujets sans pièce omis. Calculé à côté (fil virtuel), gardé 2 min par compte, attendu au plus
   350 ms : au-delà le tour part avec l'ancien bloc ou sans (l'outil `pieces` reste juste).
2. L'outil `pieces` accepte `client` et un `sujet` **nommé** (rapproché par `JarvisNameMatch` : accents, casse, tirets,
   phonétique légère) ; en portée client, chaque ligne dit son sujet (`| sujet data-platform`).
3. `afficher` : `portee.client` et `portee.sujet` nommé sont rapprochés sur l'univers **du compte** avant d'être relayés
   (`scope.client` = clé de l'univers — identifiant du poste ou `hosted` —, `clientName` = nom exact,
   `subjectId` = identifiant). L'univers n'est lu que si un nom est à rapprocher.
4. L'écran ouvre la galerie sur `GET /api/jarvis/pieces?clientId=…` sans sujet choisi, intitulée au nom du client.

### Cas d'erreur
| Situation | Comportement attendu | HTTP |
|---|---|---|
| Client ou sujet inconnu | « Client « X » inconnu ; clients : … » / « Sujet « X » inconnu. » rendus au modèle, rien relayé | — |
| Nom ambigu | Refus qui liste les candidats, rien relayé | — |
| Univers illisible | `afficher` : « refusé : Noms illisibles pour le moment. » ; `pieces` : « Sujet inconnu. » | — |
| `clientId` d'un autre compte, inconnu ou mal formé | Liste vide (indiscernable) | 200 |
| Portée client d'initiative (`appui`) | Refus `NOT_IN_SCOPE` à l'écran | — |
| Bloc trop long ou lent | Coupé à 1 400 caractères ; sans bloc au-delà de 350 ms | — |

## Contraintes de validation
| Champ | Règle |
|---|---|
| `clientId` (query) | `hosted` ou UUID d'un poste du compte ; sinon liste vide |
| `portee.client`, `pieces.client` | texte libre, rapproché ; vide = non dit |
| Bloc `## Pièces de tous les sujets` | ≤ 1 400 caractères, ≤ 40 sujets (`MAX_SUBJECTS`) |

## Critères d'acceptation
- [ ] « Montre-moi les vidéos chez Orion » ⇒ `afficher galerie`, portée client, type video (banc PIÈCES, cas C01/C02,
      C03 sujet nommé, C04 preuves d'un client).
- [ ] « Kajip », « agénor », « data platform » sont reconnus ; un nom inconnu est refusé avec la liste.
- [ ] La galerie s'ouvre sans sujet choisi au nom du client (spec écran ; banc de rendu réel après SF-253-04).
- [ ] Isolation : `clientId` d'autrui ⇒ vide ; le rapprochement ne lit que l'univers du compte.
- [ ] Définitions d'outils figées (aucune donnée volatile) ; bloc dans le message ; consigne v21.
- [ ] Bancs CONVERSATION et PIÈCES 2 × verts.

## Plan de test
- Unitaires : `JarvisNameMatchTest` (nouveau), `JarvisUniverseViewTest` (rapprochement, `resolveScope`),
  `JarvisFastPiecesTest` (bloc de tous les sujets, portée client), `JarvisFastBrainServiceTest` (galerie client
  rapprochée, refus d'un nom inconnu, bloc dans le message et pas dans la consigne), `jarvis-piece-show.spec`.
- Intégration / isolation : `JarvisPieceApiIntegrationTest.clientScopeIsIsolated` (Bob ⇒ 0, client inconnu ⇒ 0,
  identifiant mal formé ⇒ 0).
- Pré-bancs live CONVERSATION + PIÈCES (2 passages chacun).

## Impact
Endpoint `GET /api/jarvis/pieces` (paramètre `clientId`). Aucune table, aucune migration. `JarvisPieceService`,
`JarvisPieceController`, `JarvisFastPieces`, `JarvisUniverseView`, `JarvisShowCommand`, `JarvisProofGuard`,
`JarvisFastBrainPrompt` (v21, descriptions d'outils), `JarvisFastBrainService`, `JarvisPiecesBench`,
`pieces-bench.json` ; écran `jarvis-piece-client.ts`, `jarvis-piece-show.ts` ; banc de rendu `mock.mjs`, `scenario.mjs`.

Préoccupations transversales : **Contexte tenant** — composants qui résolvent le propriétaire des pièces :
`JarvisPieceService.list/listForClient/bySubject` (filtre `workspaces.list(userId)` + `userId.equals(w.getUserId())`),
`JarvisPieceController` (`currentUser.requireId()`), `JarvisFastPieces.read/allBlock` (compte de la requête),
`JarvisUniverseView.scope` (univers déjà filtré `user_id`), vérifiés par le test d'isolation.

## Hors périmètre
La vérification du nombre de pièces avant de dire « Voici » (SF-253-04).
