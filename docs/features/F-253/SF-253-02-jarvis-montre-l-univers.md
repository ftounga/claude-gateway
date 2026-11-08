# Mini-spec — F-253 / SF-253-02 Jarvis montre l'univers

## Identifiant
`F-253 / SF-253-02`

## Feature parente
`F-253` — Jarvis montre vraiment (cadrage §3 #1 et §6 D1)

## Statut
`in-review` · **Estimation :** 1,5 j · **Branche :** `feat/SF-253-02-montrer-l-univers` · **Dépend de :** SF-253-01

## Objectif
Le cerveau rapide peut mettre au premier plan une vue de l'univers (« mes clients », « mes sujets », « qui est
connecté »…) via une action `univers` de l'outil `afficher`, vérifiée contre l'univers du compte, appliquée par le
« Montrer » F-234 en 2D comme en 3D.

## Comportement attendu

### Cas nominal
1. `afficher` gagne l'action `univers`, un champ `vue` (énumération fermée `clients`, `sujets`, `connectes`,
   `deconnectes`, `chantiers-bloques`, `etapes-en-cours`, `a-envoyer`) et un champ `client` facultatif (D1 : définition
   figée, aucun outil nouveau, consigne v20).
2. La passerelle confronte l'ordre à l'univers du compte (`JarvisUniverseService`, filtré `user_id`) et rend au modèle
   ce qui va apparaître : « affiché : 2 clients — CAGIP, Edenred ». Le client est rapproché du nom dit (`JarvisNameMatch`,
   tolérant accents, casse, tirets, phonétique légère) et relayé au **nom exact**.
3. L'événement SSE `show` porte `view` et `client` ; `applyShow` traduit (`universeShowRequest`) et pose le « Montrer »
   sur le bus (`showRequest`) : constellation 2D (nœuds au premier plan, fil d'Ariane) et 3D (`directShow`). La vue
   `sujets` est nouvelle à l'écran : noms écrits, sujets hors de l'anneau (grappe, téléphone) sur l'anneau des marques.
4. Un client hébergé ne compte ni connecté ni déconnecté (mêmes nombres passerelle et écran).

### Cas d'erreur
| Situation | Comportement attendu |
|---|---|
| Vue absente ou inconnue | Refus `NO_VIEW` rendu au modèle, rien relayé |
| Client nommé inconnu ou ambigu | « refusé : client « X » inconnu ; clients : … », rien relayé |
| Vue vide (aucun chantier bloqué, rien à envoyer) | « rien à montrer : … », rien relayé |
| Poste : 0 connecté | Relayé : le titre le dit (« 0 poste connecté sur 2 ») |
| Présentation | « refusé : en présentation, l'univers n'est pas montré » ; la page refuse aussi |
| Univers illisible (banc, panne) | Relayé, résultat « univers non relu » ; la page résout sur son univers, sinon ne montre rien |

## Critères d'acceptation
- [ ] « Montre-moi la liste de mes clients » ⇒ action `univers`, vue `clients` ; l'écran met les clients au premier plan.
- [ ] « Montre-moi mes sujets » ⇒ vue `sujets` ; les noms des sujets s'écrivent, y compris d'un autre client et au téléphone.
- [ ] Le modèle reçoit le vrai résultat (noms et nombre), ou le refus motivé.
- [ ] Isolation : seule la lecture `universe(userId)` alimente la vérification ; le nom d'un autre compte est inconnu.
- [ ] Définitions d'outils figées d'un appel à l'autre ; consigne versionnée.
- [ ] Pré-banc PIECES (catégorie UNIVERS incluse) vert 2 fois de suite.

## Plan de test
- Unitaires backend : `JarvisUniverseViewTest` (vues, client rapproché, inconnu, vide, présentation), `JarvisShowCommandTest`,
  `JarvisPiecesBenchTest`, `JarvisFastBrainServiceTest` (version, préfixe figé).
- Unitaires écran : `jarvis-show.spec`, `jarvis-scene-bus.service.spec`, `jarvis-show-3d.spec`, `jarvis.component.spec`.
- Isolation : `universe()` est lu par `user_id` (JarvisUniverseService) ; aucune autre source.
- Banc de rendu réel : `BANC_MODE=apres node scripts/banc-jarvis-montre/scenario.mjs` (OK pour clients et sujets, PC et téléphone).
- Pré-banc live : `JarvisPiecesBenchLiveTest`, 2 passages verts.

## Impact
Backend : `JarvisFastBrainPrompt`, `JarvisShowCommand`, `JarvisFastBrainService`, `JarvisUniverseView` (nouveau),
`JarvisNameMatch` (nouveau), `JarvisPiecesBench`, `pieces-bench.json`. Écran : `jarvis-show.ts`, `jarvis-scene-reader.ts`,
`jarvis-scene-bus.service.ts`, `jarvis-constellation.component.ts`, `jarvis-show-3d.ts`, `jarvis-piece-show.ts`,
`jarvis.component.ts`. Aucune table.
Préoccupations transversales : aucune (ni auth, ni tenant, ni plans, ni navigation).

## Hors périmètre
Pièces d'un client et noms rapprochés pour `pieces`/`afficher galerie` (SF-253-03) ; vérification de tous les ordres
(SF-253-04) ; animation par la réponse rapide (SF-253-07).
