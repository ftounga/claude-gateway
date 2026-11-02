# Mini-spec — [F-238 / SF-238-04] Routage inter-zones et étiquettes

## Identifiant
SF-238-04

## Feature parente
F-238 — Architecture en 3D, itération 0 « Le schéma 2D cadré » (cadrage §4.2 étapes 3-4, §4.4, §4.6 G3)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-238-04-routage-etiquettes`

---

## Objectif

Rendre les liaisons lisibles : étiquettes posées sans rien chevaucher, aucune liaison sous une icône ni le long
d'une bordure, faisceaux à pas constant, et un rendu en 2 s au plus aux bornes de F-142.

## Comportement attendu

### Cas nominal
1. **Étiquettes de flux** (`placeLabels`) : sur le plus long segment de leur liaison, centrées ; à défaut glissées
   le long du segment (pas de 8 px), puis sur les segments suivants, puis **à côté** du trait ; une position est
   écartée si elle chevauche une icône, un libellé de nœud, un bandeau de zone ou une étiquette déjà posée ; à
   égalité, celle qui masque le moins d'autres traits. Une seule étiquette par liaison.
2. **Routeur** (posé en SF-238-03) affiné :
   - bandeau de zone : la zone est élargie de 48 px au-delà de son libellé — il reste toujours une place pour
     traverser la bordure haute sans la longer ;
   - recherche dans une **fenêtre** de 320 px autour des extrémités, puis sur toute la grille si besoin ;
     états remis à zéro par génération (pas de remplissage de la grille par liaison) ; état fermé une fois ;
   - heuristique **pondérée** : 1,2 (quasi exact) jusqu'à 40 liaisons, 4 au-delà (rendu < 2 s à 60/120) ;
   - bidirectionnel : une liaison, deux pointes ; liaisons parallèles : canaux voisins (jamais superposées).
3. **Composition** : au plus 4 unités côte à côte sur un rang du flux (au-delà, le rang passe à la ligne) ; les
   nœuds sans lien interne s'alignent sans ELK (même résultat, plus rapide).
4. Garde **G3** (`gardes.g3Collisions`) : étiquette sur icône / libellé / bandeau / autre étiquette ; liaison sous une
   icône ; liaison qui longe une bordure (≤ 8 px) sur plus de 16 px.

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Aucune position d'étiquette libre | Première candidate retenue ; G3 le signale (tests) |
| Fenêtre sans chemin | Recherche sur toute la grille ; sinon coude droit direct + note |

## Critères d'acceptation
- [x] 0 collision d'étiquette (G3) sur les 3 schémas + « tout » (et le 3-tiers en TB).
- [x] Aucune liaison sous une icône (y compris sur le grand schéma 60/120).
- [x] Aucune liaison qui longe une bordure sur plus de 16 px (3 + « tout »).
- [x] ≤ 2 s de rendu pour 60 nœuds / 120 liens (service en régime ; ELK compile une fois par processus, ≈ 0,7 s).
- [x] Faisceau : 3 liaisons entre deux mêmes nœuds jamais superposées ; bidirectionnel = 1 trait, 2 pointes.

## Périmètre — hors scope
`drawio` cadré et export du graphe (SF-238-05) ; consigne de l'agent et bascule (SF-238-06).

## Contraintes de validation
Inchangées (bornes F-142).

## Technique
- Endpoints / tables / migration : aucun.
- Fichiers : `cadre.js` (`placeLabels`), `cadre-routage.js` (fenêtre, générations, pondération),
  `cadre-composition.js` (rangs bornés, alignement sans ELK, marge de bandeau), `gardes.js` (G3),
  `tests/test_cadre_routage.js`, `package.json` (`npm test` en série).

## Plan de test
- node:test : G3 sur 4 schémas + 3-tiers TB, étiquette près de sa liaison, faisceau, bidirectionnel, performance
  (grand schéma déterministe 60/120, 24 zones, 3 AZ), G3 non aveugle ; non-régression `test_cadre*.js` et Python.
- Isolation utilisateur : non applicable.

## Dépendances
SF-238-03 (mergée).

## Notes et décisions
- **A1** A* pondéré au-delà de 40 liaisons : les chemins restent orthogonaux et propres, un peu moins courts que
  l'optimum ; c'est le prix des 2 s. Réversible (constantes).
- **A2** Le temps est mesuré service en régime : la première mise en page ELK d'un processus coûte ≈ 0,7 s (compilation
  JIT), une fois par démarrage du pod.
- **A3** `npm test` en série : le test de performance mesure un temps ; en parallèle, les fichiers se disputent le CPU.
