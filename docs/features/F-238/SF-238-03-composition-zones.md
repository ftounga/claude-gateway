# Mini-spec — [F-238 / SF-238-03] Composition des zones

## Identifiant
SF-238-03

## Feature parente
F-238 — Architecture en 3D, itération 0 « Le schéma 2D cadré » (cadrage §4.2 étapes 1-3, §4.4, §19 Q2)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-238-03-composition-zones`

---

## Objectif

Poser l'arbre des zones nous-mêmes (AZ parallèles et égales, public avant privé, bande régionale, flux) puis les
nœuds par ELK dans chaque zone, sur une grille de 8 px, et router les liaisons avec un routeur orthogonal maison.

## Comportement attendu

### Cas nominal
1. **Composition** (`cadre-composition.js`, pur, déterministe), par zone, de l'intérieur vers l'extérieur :
   - nœuds propres de la zone placés par **ELK `layered`** (liens internes seulement), positions **sur la grille de
     8 px** ; cellule 128 × 112 (largeur multiple de 16 : le centre tombe sur la grille) ;
   - **AZ sœurs (≥ 2)** : côte à côte sur l'axe transverse (colonnes en TB, bandes en LR), **taille égale** ; deux AZ
     de **même gabarit** (mêmes types par sous-réseau) reçoivent **la même mise en page interne** (copie
     géométrique, correspondance par type) — jamais de composant ajouté ni déplacé ;
   - **sous-réseaux (≥ 2)** : publics puis privés, dans le sens du flux, même largeur ;
   - **bande régionale** : dans une Région qui contient d'autres zones, ses nœuds propres longent le reste (TB : à
     droite ; LR : dessous) ;
   - les autres unités suivent le **flux** : tri topologique des liens entre unités (à égalité, l'ordre de l'auteur),
     puis **rangs** (plus long chemin) ; les unités d'un même rang sont côte à côte. Les acteurs externes arrivent
     donc à l'entrée, hors d'AWS Cloud.
   - marges : 48 px sous le bandeau, 24 px ailleurs ; 48 px entre unités (la place d'un faisceau).
2. **Sens par défaut** (Q2) : `direction` absente ⇒ TB pour un schéma à ≥ 2 AZ, LR sinon ; RL ⇒ LR, BT ⇒ TB.
3. **Routeur** (`cadre-routage.js`) : A* sur la grille de 8 px, états (case, direction), pénalité de coude ;
   obstacles = icônes et libellés (à 8 px), bandeaux de zone ; bordure traversée **perpendiculairement** seulement ;
   longer une bordure à 8 px pénalisé ; sortir d'une zone commune ou entrer dans une zone étrangère pénalisé ;
   superposition à une liaison existante très pénalisée (faisceaux à pas de 8 px), croisement un peu ; ports sur le
   bord de l'icône (haut, gauche, droite) ou sous le libellé, côté du flux préféré, port déjà pris évité. Échec
   (aucun chemin) ⇒ coude droit direct + note (jamais d'oblique).
4. Étiquettes : au milieu du plus long segment (affiné en SF-238-04).
5. Le repli ELK du graphe entier reste accessible (`build(prepared, {mode: "elk"})`, cadrage §22).

### Cas d'erreur

| Situation | Comportement |
|---|---|
| Aucun chemin pour une liaison | Coude droit direct, note « tracée sans contournement » (comptée) |
| AZ de gabarits différents | Taille égale, mises en page internes propres (aucune symétrie forcée) |
| Cycle de flux entre unités | Ordre de l'auteur pour départager |

## Critères d'acceptation
- [x] 3-tiers : AZ A et B **côte à côte** (TB) / en bandes parallèles (LR), de taille égale ; homologues **sur la même
      ligne** (G5) ; aucune AZ dans le sens du flux.
- [x] S3 dans la bande régionale ; utilisateurs hors AWS Cloud, à l'entrée.
- [x] Public avant privé, dans les deux sens.
- [x] G1, G2, G4, G5, G6 verts sur les 3 types + « tout » ; toutes les liaisons routées.
- [x] Deux AZ différentes : aucun composant ajouté ni déplacé.
- [x] Captures (planche finale en SF-238-06).

## Périmètre — hors scope
- Placement des étiquettes sans collision, garde G3, liaison qui longe une bordure, performance 60/120 (SF-238-04).

## Contraintes de validation
Aucune nouvelle entrée ; `direction` ∈ LR, RL, TB, BT ou absente.

## Technique
- Endpoints / tables / migration : aucun.
- Fichiers : `cadre-composition.js` (nouveau), `cadre-routage.js` (nouveau), `cadre.js` (`layoutCadre`, sens par
  défaut, `build` à deux modes), `gardes.js` (G5), `tests/test_cadre_composition.js`.

## Plan de test
- Unitaires node:test : gardes sur 4 schémas, AZ parallèles (TB et LR), égalité de taille, homologues, public/privé,
  bande régionale, acteurs externes, sens par défaut, symétrie qui ne ment pas, 3 AZ, G5 non aveugle, repli ELK.
- Non-régression : `test_cadre*.js`, tests Python `test_cloud*.py`.
- Isolation utilisateur : non applicable.

## Dépendances
SF-238-01, SF-238-02 (mergées).

## Notes et décisions
- **A1** Le routeur maison (prévu en SF-04) est posé ici : sans lui, la composition casse le routage ELK du graphe
  entier. SF-04 l'affine (étiquettes, G3, longement de bordure, performance).
- **A2** Écart entre unités 48 px (cadrage : 24 px entre zones sœurs) : la place de 2-3 canaux de routage ; G4
  (≥ 24 px) respectée. Réversible (constante).
- **A3** Unités d'un même rang côte à côte (ex. Utilisateurs et Centre de données, tous deux en entrée) : sinon une
  liaison traverse une zone étrangère.
- **A4** Copie géométrique des AZ jumelles plutôt qu'un ELK par AZ : ELK peut ranger différemment deux AZ dont les
  liens internes diffèrent (ex. réplication RDS A → B), ce qui casserait l'alignement des homologues.
