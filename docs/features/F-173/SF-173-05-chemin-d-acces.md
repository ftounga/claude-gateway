# Mini-spec — [F-173 / SF-173-05] Le chemin d'accès

## Identifiant

`F-173 / SF-173-05`

## Feature parente

`F-173` — La carte vivante (cadrage `CADRAGE-F-173-la-carte-vivante.md`, D1→D7 validées le 2026-10-04)

## Statut

`done`

## Date de création

2026-10-04

## Branche Git

`feat/SF-173-05-chemin-d-acces`

---

## Objectif

Répondre dans la fiche à « **comment j'atteins X** » : la chaîne *poste → proxy → VPN / bastion → compte → cluster → X* tirée des relations de la carte, chaque tronçon dit **ouvert**, **fermé** ou **inconnu**.

---

## Comportement attendu

### Cas nominal

1. Dans la fiche (SF-173-03), une section **« Comment l'atteindre »** affiche la chaîne, calculée côté écran sur le plan déjà lu (aucun appel de plus).
2. **Construction** :
   - la **remontée** : X, puis ses parents (relations `dans` / `heberge`) jusqu'à la racine ;
   - l'**entrée** : le plus court chemin (liens de toute nature, dans les deux sens) depuis la remontée vers une ressource d'**accès** — type `proxy`, `acces`, `hote`, ou libellé contenant `vpn`, `bastion`, `proxy`, `jump` ;
   - la chaîne affichée : **Ce poste** → (chemin d'entrée) → remontée inversée → X, sans doublon.
3. **Tronçons** : chaque étape porte un état tiré de l'état de la ressource : `joignable` / `actif` → **ouvert** (pastille succès, « ouvert » écrit) ; `injoignable` / `obsolete` → **fermé** (rouge erreur, « fermé » écrit) ; sinon **inconnu** (gris, « inconnu » écrit). « Ce poste » est le point de départ.
4. Sans ressource d'accès atteignable : un tronçon « Accès non cartographié » (inconnu) s'insère entre « Ce poste » et la remontée, et une phrase le dit.
5. Chaque étape est cliquable (sauf « Ce poste » et « Accès non cartographié ») et mène à sa fiche.

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Plan non chargé | section absente |
| Cycle dans les liens | parcours borné (chaque ressource visitée une fois) |
| X est lui-même une ressource d'accès | chaîne *Ce poste → X* |

---

## Critères d'acceptation

- [x] La chaîne va de « Ce poste » à X, en passant par l'accès le plus proche puis les parents.
- [x] Chaque tronçon dit ouvert / fermé / inconnu en toutes lettres (jamais la couleur seule).
- [x] Sans accès cartographié, la chaîne le dit au lieu d'inventer.
- [x] Aucun appel réseau supplémentaire.

---

## Contraintes de validation

| Élément | Règle |
|---------|-------|
| Profondeur de recherche de l'entrée | ≤ 6 liens |
| Étapes affichées | ≤ 12 |

---

## Périmètre

### Hors scope (explicite)

- Tout test réel de connectivité (le produit ne sonde rien : l'état est celui écrit dans la carte).
- Le surlignage du chemin sur le canevas.

---

## Technique

### Contrat API

Importé de SF-173-01 (plan), sans modification.

### Composants frontend

- `postes/forge-map/forge-map-path.ts` : fonction pure `accessPath(graph, nodeId)`.
- `postes/forge-map/forge-map.component.html` : section projetée dans la fiche (`[mapCardPath]`).

### Préoccupations transversales

Aucune (ni auth, ni tenant, ni plans, ni routing : les clics réutilisent `?noeud=`).

---

## Plan de test

- [x] `forge-map-path.spec.ts` : chaîne complète proxy → compte → cluster, états des tronçons, accès absent, X ressource d'accès, cycle, borne.
- [x] `forge-map.component.spec.ts` : la section s'affiche dans la fiche et une étape navigue.
- [x] `npm run build && npm test` verts.

## Dépendances

SF-173-03 mergée.

## Notes et décisions

- **Arbitrage (réversible)** : l'état d'un tronçon est celui de la ressource d'arrivée, tel qu'écrit dans la carte — jamais une sonde.
