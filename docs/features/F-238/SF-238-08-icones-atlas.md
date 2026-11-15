# Mini-spec — [F-238 / SF-238-08] Icônes servies et atlas

## Identifiant
SF-238-08

## Feature parente
F-238 — Architecture en 3D, itération 1 (cadrage §5.4, §14, §15, §17)

## Statut
done

## Date de création
2026-11-15

## Branche Git
`feat/SF-238-08-icones-atlas`

---

## Objectif

La scène 3D reçoit les icônes officielles AWS (et autres fournisseurs de F-142) par la passerelle, et les compose en
**un seul atlas** (une texture, un appel de dessin), sans jamais remplacer une icône absente par une autre.

## Comportement attendu

### Cas nominal
1. **Proxy** `GET /api/diagrams/icons/{chemin}.png` (ex. `/api/diagrams/icons/aws/database/rds.png`, `chemin` =
   le champ `resolved` du graphe SF-238-07) : la passerelle demande l'icône à la route `/icons` du renderer (F-215,
   **même client** `VideoIconSource`, même liste blanche `aws|azure|gcp|k8s|onprem|generic`, même motif de nom,
   ≤ 512 Kio) et la sert : `200 image/png`, `Cache-Control: public, max-age=31536000, immutable`,
   `X-Content-Type-Options: nosniff`. Aucune donnée utilisateur. Sous `/api` authentifié (comme le reste).
2. Petit cache mémoire borné (256 icônes) côté passerelle : une icône déjà servie ne rappelle pas le renderer.
3. **Atlas** (front, module pur `frontend/src/app/jarvis/stage3d/architecture/architecture-icon-atlas.ts`, sans
   `three`, testable sous Karma sans WebGL) :
   - `planIconAtlas(nodes)` : icônes **distinctes** des nœuds (`resolved` valide, non `unknown`), ordre trié
     (déterministe), ≤ 62 icônes ; cellule **0 = boîte neutre**, **1 = type inconnu (pointillé)**, icônes à partir de
     2 ; grille carrée en puissance de deux, cellules 128 px ⇒ ≤ 64 cellules ⇒ ≤ 1024 × 1024 ;
   - `composeIconAtlas(plan, images, doc)` : dessine chaque icône **non recolorée**, centrée avec marge, dans sa
     cellule ; une icône absente ou illisible ⇒ sa cellule n'est pas dessinée et `cellOf` rend **0** (boîte neutre) ;
   - `loadIconAtlas(nodes, fetchIcon, doc)` : charge en parallèle, compose, mesure (`composeMs`, `totalMs`) et rend un
     objet conforme à l'interface `ArchitectureIconSource` de la scène (`image`, `columns`, `rows`, `cellOf`) + `uv()`
     (coordonnées de texture, `flipY` de `CanvasTexture`) et `missing` ;
   - `iconUrl(resolved)` / `httpIconFetcher(headers)` : l'URL du proxy (refus local d'un chemin hors motif) et le
     chargeur `fetch` sous JWT ⇒ `createImageBitmap`.
   Le branchement dans la scène (`new CanvasTexture(atlas.image)`) est fait par SF-238-09/10 (autre agent).

### Cas d'erreur

| Situation | Comportement | Code |
|---|---|---|
| Nom hors motif (majuscules, caractères, profondeur, fournisseur hors liste) | Refus, renderer non appelé | 400 |
| Traversée (`..`, `.`, `%2e%2e`, `\`) | Refus (motif, ou pare-feu HTTP) | 400 |
| Icône absente du paquet | Introuvable — **jamais une autre icône** | 404 |
| Renderer non configuré / muet | Indisponible | 503 |
| Front : icône absente / erreur réseau / chemin invalide | Boîte neutre (cellule 0) | — |
| Front : `unknown: true` | Boîte en pointillé (cellule 1) | — |

## Critères d'acceptation
- [x] Nom hors motif ⇒ 400 (test) ; traversée ⇒ 400 (test) ; renderer jamais appelé dans ces cas (test).
- [x] Icône absente ⇒ 404 au proxy ; dans l'atlas ⇒ boîte neutre, jamais une autre icône (test : la cellule d'un
      nœud dont l'icône manque est 0, aucune autre icône n'est dessinée à sa place).
- [x] Réponse 200 : `image/png`, `public, max-age=31536000, immutable`, `nosniff` (test d'intégration).
- [x] Atlas : 60 icônes distinctes ⇒ une seule image ≤ 1024 × 1024, cellules 128 px, déterministe (même graphe ⇒ même
      plan) (test).
- [x] Atlas de 60 icônes composé en ≤ 300 ms sous Karma (Chrome headless, images synthétiques) ; mesure `composeMs` /
      `totalMs` exposée pour le banc téléphone de SF-238-11 (voir N2).

## Périmètre — hors scope
- Branchement de l'atlas dans la scène et la visionneuse (SF-238-09/10).
- Halo et socle holographiques (scène) ; aucune recoloration d'icône ici.
- Aucun changement du renderer (route `/icons` de F-215 réutilisée telle quelle).

## Contraintes de validation

| Champ | Règle |
|---|---|
| `chemin` | `^[a-z0-9]+(/[a-z0-9][a-z0-9._-]*){1,3}$`, fournisseur ∈ liste blanche, aucun segment `.`/`..` (motif de `icons.js`) |
| Taille icône | ≤ 512 Kio (client F-215) |
| Atlas | ≤ 62 icônes + 2 cellules neutres, 128 px |

## Technique
- **Endpoint nouveau** : `GET /api/diagrams/icons/**` (`DiagramIconController`, package `diagrams`).
- **Tables** : aucune.
- **Fichiers** : `DiagramIconController.java`, `DiagramIconProxy.java` (motif + cache, délègue à `VideoIconSource`) ;
  `architecture-icon-atlas.ts` + `.spec.ts`.
- **Préoccupations transversales** : Auth — endpoint sous `/api` authentifié par la chaîne existante, aucune règle de
  sécurité modifiée (`SecurityConfig` inchangé). Contexte tenant — non concerné : aucune donnée utilisateur servie.
  Navigation — non (aucune route front). Plans/limites — non.

## Plan de test
- **Unitaires** : `DiagramIconProxyTest` (motif : valides/invalides, traversée, fournisseur, cache, absente, muet).
- **Intégration** (`DiagramIconApiIntegrationTest`, Spring complet, `VideoIconSource` simulé) : 200 + en-têtes ; 400
  hors motif ; 400 traversée (`..` brut et encodé) ; 404 absente ; 401 sans jeton ; renderer non appelé sur refus.
- **Front (Karma)** : plan (dédoublonnage, tri, cellules 0/1, taille), `cellOf` (absente ⇒ 0, inconnu ⇒ 1), `uv`,
  composition (pixels : cellule d'une icône absente vide), chargeur (erreur ⇒ neutre), `iconUrl` (refus), perf 60.
- **Isolation utilisateur** : non applicable (contenu public du paquet d'icônes, aucune donnée de compte).

## Dépendances
F-215 (route `/icons`, mergée), SF-238-07 (champ `resolved` conservé, mergée). Indépendante de SF-238-09.

## Notes et décisions
- **N1** Réutilisation de `VideoIconSource` (F-215) plutôt qu'un second client HTTP : même liste blanche, même borne.
- **N2** Le « ≤ 300 ms sur le téléphone du PO » ne se mesure pas en CI : le module expose `composeMs`/`totalMs`, le
  test Karma borne la composition à 300 ms sur PC, et la mesure téléphone se fait au banc de SF-238-11.
- **N3** Les cellules 0 (neutre) et 1 (inconnu) reprennent le contrat de `neutralIconSource` (SF-238-09) : l'atlas se
  substitue à lui sans changer la scène.
