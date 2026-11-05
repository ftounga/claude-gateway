# Mini-spec — F-248 / SF-248-07 Les faits du registre (F-247)

## Identifiant
`F-248 / SF-248-07`

## Feature parente
`F-248` — Les pancartes (cadrage §4.1, §4.2, §4.5 ; D2, D15 ; porte Z12 de F-247)

## Statut
`done` (2026-11-05) · **Estimation :** 1,5 j (réestimé ¾ j sur le code) · **Branche :** `feat/SF-248-07-faits-du-registre` · **Dépend de :** SF-248-02, SF-247-10 (mergée)

## Objectif
Les faits du registre d'état F-247 deviennent des pancartes — ticket, MR, pipeline, déploiement, résultat — depuis le
bloc « État connu » **préparé** (lecture d'office, en mémoire) et l'outil `etat` (lecture **ciblée**, tout le résultat),
avec lien relevé (`url`, D15), mention « poste éteint depuis » et plein écran par index.

## Comportement attendu

### Cas nominal
1. `JarvisStateCards` (pur) : un objet du registre (attributs regroupés) ⇒ une fiche ; famille → type (`TICKET`, `MR`,
   `PIPELINE`, `DEPLOYMENT|RELEASE` → `DEPLOIEMENT`, `ACCESS` → `ACCES`, attribut `result` → `RESULTAT`, sinon
   `GENERIQUE`) ; titre « PROJ-123 — Migration SSO » ; statut = `status|state|result|health|sync` ; lien = `url`
   relevée (http(s)), sinon le sujet, sinon aucun ; alias = clé dicible, clé brute, titre ; id
   `fait:etat:<host>.<clé base64url>`.
2. **Bloc (Z12)** : `JarvisStateBlock.compose` garde, dans le même cache que le texte, les faits de chaque objet ;
   `factsFor(userId, lignes)` les rend **en mémoire** ; la réplique en fait des fiches d'office (montrées si citées).
   Poste déconnecté parmi les lignes retenues ⇒ `JarvisCardCatalog.note("Poste CAGIP éteint depuis 18 h 05")`, portée
   par l'événement (`offline`) et affichée **une fois** en pied du jeu.
3. **Outil `etat`** : les faits déjà lus pour fabriquer le résultat deviennent des fiches **ciblées** (tout le résultat).
4. **Plein écran** : `JarvisStateCardResolver` (famille `etat`) relit par `byKey (user_id, host_id, clé)` — tous les
   attributs libellés, la valeur précédente du statut et son heure, le client, le sujet ; un accès ne rend que sa liste
   blanche.

### Cas d'erreur
| Situation | Comportement attendu | Code HTTP |
|---|---|---|
| Fait ou client d'un autre compte | introuvable | 404 |
| Référence `etat` illisible | introuvable | 404 |
| Cache du bloc absent (pod neuf) | aucune fiche de bloc (la réplique ne lit jamais le registre) | — |
| Registre coupé (client ou global) | aucune fiche | — |

## Contraintes de validation
≤ 20 lignes du bloc, ≤ 50 faits de l'outil (bornes F-247) ; ≤ 24 fiches par réponse (catalogue).

## Critères d'acceptation
- [x] Outil `etat` : une fiche par objet, toutes ciblées, gabarits ticket / MR / déploiement, url relevée, groupe = client.
- [x] Bloc : faits rendus en mémoire, rien pour un autre compte ; mention « éteint depuis » une fois.
- [x] Plein écran par index, valeur précédente ; 404 pour Bob et pour un client de Bob.

## Plan de test
- Intégration : `JarvisStateCardsIntegrationTest` (3, deux utilisateurs, deux clients).
- Unitaires écran : `jarvis-card-deck.spec.ts` (+1 : pied du jeu).
- Non-régression : `JarvisStateBlockTest`, `JarvisFastBrainStateTest`, `JarvisStateApiIntegrationTest`, suites complètes.

## Impact
- Backend : `jarvis/cards/JarvisStateCards`, `JarvisStateCardResolver` ; `JarvisCardCatalog` (`note`, `Event.offline`) ; `JarvisStateBlock` (`factsFor`, `preparedClients`, `label` public) ; `JarvisStateTool` (collecte ciblée) ; `JarvisFastBrainService.stateBlock` (collecte d'office).
- Frontend : `CardsEvent.offline`, `CardShowing.offline`, `JarvisPieceStore.deckNote`, pied du jeu dans la galerie.
- Préoccupations transversales : **Contexte tenant** — `JarvisStateRegistry.byKey/recent` (`user_id` + `host_id`), `RunnerHostService.list(userId)`, `WorkspaceRepository.findByIdAndUserId` ; test à deux utilisateurs et deux clients.

## Arbitrages (réversibles)
- Le cache Z12 garde les **faits** (pas les fiches) : la fiche se fabrique en mémoire à la lecture (microsecondes), sans toucher au record `Prepared` ni à ses tests.
- La clé du registre est encodée en base64url dans l'identifiant (elle peut contenir « / » et « : »).

## Hors périmètre
Opus et `JarvisStateNote` (SF-08) ; les accès du poste (SF-09).
