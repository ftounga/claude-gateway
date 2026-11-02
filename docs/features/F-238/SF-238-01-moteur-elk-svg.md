# Mini-spec — [F-238 / SF-238-01] Moteur ELK et SVG maison

## Identifiant
SF-238-01

## Feature parente
F-238 — Architecture en 3D, itération 0 « Le schéma 2D cadré » (cadrage §4.2, §4.6, §17)

## Statut
done

## Date de création
2026-11-02

## Branche Git
`feat/SF-238-01-moteur-elk`

---

## Objectif

Rendre les schémas `engine=cloud` avec un moteur maison (ELK `layered`, liaisons **orthogonales**, SVG dessiné par
nous) derrière un interrupteur, sans rien changer au rendu graphviz tant que l'interrupteur n'est pas basculé.

## Comportement attendu

### Cas nominal

1. La gateway lit `app.diagrams.cloud-layout` (`APP_DIAGRAM_CLOUD_LAYOUT`, défaut `graphviz`). À `elk`, elle ajoute
   `"layout": "elk"` au corps posté au renderer pour `engine=cloud` ; sinon le corps est inchangé.
2. Le renderer, sur `layout: "elk"` :
   - `cloud.py --prepare` valide la description (mêmes bornes et refus que graphviz) et résout les icônes
     (alias SF-142-16, repli SF-142-12) : il rend des **données** (chemin du PNG réellement utilisé, famille,
     `unknown`) ;
   - `cadre.js` met en page avec ELK (`layered`, `edgeRouting=ORTHOGONAL`, imbrication `INCLUDE_CHILDREN`) et
     dessine le SVG : icônes inlinées en `data:`, liaisons en `polyline` (coudes droits, une pointe homogène),
     étiquettes sur fond blanc ; identifiants stables `zone-<id>`, `node-<id>`, `lien-e<i>` ;
   - il construit aussi le **graphe** `ArchitectureGraph` v1 (cadrage §5.1) — non renvoyé (SF-238-05) ;
   - gardes **G1** (orthogonalité) et **G2** (extrémités) vérifiées à chaque rendu : un échec est **compté et
     journalisé** (`CADRE_GARDE …`), le SVG part quand même.
3. Mêmes en-têtes qu'avant : `X-Cg-Unknown-Types` (types sans icône, avec pistes), `X-Cg-Diagram-Notice` (densité
   SF-142-17/19, reprise).
4. Profondeur d'imbrication portée de 4 à **6** (amendement SF-142-15, cadrage §1.4) : la topologie AWS canonique
   (AWS Cloud › Région › VPC › AZ › sous-réseau) n'est plus refusée — pour les deux moteurs.

### Cas d'erreur

| Situation | Comportement | Code |
|---|---|---|
| Lien vers un nœud non déclaré, borne dépassée, cycle | Refus avec la raison (même texte que graphviz) | 422 |
| Type sans icône | Forme en pointillé rouge « ? » + libellé, signalée dans `X-Cg-Unknown-Types` ; jamais une icône approchante | 200 |
| `strict: true` et type inconnu | Refus avec suggestions | 422 |
| Réglage `cloud-layout` inconnu | Vaut `graphviz` | — |
| Segment oblique / extrémité détachée (garde) | Journal `CADRE_GARDE`, SVG livré | 200 |

## Critères d'acceptation

- [x] Sur les 3 schémas types (`tests/fixtures-cadre/`) : **0 segment non orthogonal** (G1), **0 extrémité
      détachée** (G2), chaque lien tracé.
- [x] Même entrée ⇒ même SVG octet pour octet (et même graphe).
- [x] `zone-<id>` / `node-<id>` présents pour toute zone et tout nœud.
- [x] `graphviz` rend exactement comme avant : les 89 tests Python `test_cloud*.py` restent verts ; aucun champ
      `layout` posté par défaut.
- [x] Types inconnus toujours signalés (SF-142-09/12).
- [x] Libellés échappés (contenu et attributs) : un libellé hostile ne produit aucune balise.

## Périmètre

### Hors scope (explicite)
- Styles AWS des zones, `kind`, icônes de groupe, imbrication stricte (SF-238-02).
- Composition des zones (AZ parallèles, bande régionale, grille) et routeur maison (SF-238-03/04).
- Moteur `drawio` cadré et renvoi du graphe (SF-238-05) ; consigne de l'agent et bascule du défaut (SF-238-06).

## Contraintes de validation

| Champ | Règle |
|---|---|
| `app.diagrams.cloud-layout` | `graphviz` \| `elk` (insensible à la casse) ; toute autre valeur ⇒ `graphviz` |
| Bornes de la `spec` | Inchangées F-142 : 60 nœuds, 24 groupes, 120 liens, libellés 120 caractères ; profondeur **6** |

## Technique

### Endpoint(s)
Aucun endpoint public nouveau. Contrat interne gateway → renderer : `POST /render` accepte `layout: "elk"` pour
`engine=cloud`.

### Tables impactées
Aucune.

### Migration Liquibase
- [x] Non applicable

### Fichiers
- `diagram-renderer/cadre.js` (nouveau), `gardes.js` (nouveau), `cloud.py` (`--prepare`, profondeur 6),
  `server.js` (branche `elk`), `Dockerfile` (`elkjs@0.9.3` épinglé), `package.json` (tests locaux).
- `backend/.../diagrams/DiagramProperties.java` (`cloudLayout`), `HttpDiagramRenderer.java`, `application.yml`.

## Plan de test

### Tests unitaires
- [x] `tests/test_cadre.js` : G1/G2 sur les 3 types, déterminisme, identifiants, `data:` seulement, type inconnu,
      échappement, refus, profondeur 5 acceptée, graphe (familles, polylignes), G1 sait voir une oblique/courbe,
      densité.
- [x] `HttpDiagramRendererTest` : défaut graphviz sans `layout` ; `elk` posté ; valeur inconnue ⇒ graphviz.
- [x] Non-régression : `python3 -m unittest discover -s tests -p "test_cloud*.py"` (89 tests).

### Tests d'intégration
- [x] Suite backend complète (`mvn test`).

### Isolation utilisateur
- [x] Non applicable — aucune donnée lue ni écrite ; le renderer ne connaît aucun compte.

## Dépendances
- Aucune SF bloquante (itération 0 indépendante de F-237).
- Questions ouvertes : aucune.

## Préoccupations transversales
- Auth / tenant / plans / navigation : **non concernées** (aucun endpoint, aucune donnée).
- Livrables existants : aucun changement tant que `cloud-layout=graphviz` (défaut).

## Notes et décisions
- **A1** Le mode `--prepare` garde validation et résolution d'icônes dans `cloud.py` : un seul endroit décide ce
  qu'est un type et ce qui est refusé, quel que soit le moteur.
- **A2** Le nom de réglage suit le cadrage (`APP_DIAGRAM_CLOUD_LAYOUT`), sous la clé existante `app.diagrams`.
- **A3** La profondeur 6 (prévue en SF-238-02) est avancée ici : les critères de SF-01 portent sur le schéma
  3-tiers, à 5 niveaux.
- **A4** Dans cette SF, la mise en page est ELK sur le graphe entier (le « repli » du cadrage §22) ; la composition
  des zones (SF-03) et le routeur maison (SF-04) la remplacent.
