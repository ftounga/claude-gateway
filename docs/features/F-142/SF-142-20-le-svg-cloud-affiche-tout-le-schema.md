# Mini-spec — [F-142 / SF-142-20] Le SVG cloud affiche TOUT le schéma (viewBox cohérente)

> Correctif d'un défaut introduit par SF-142-18. Renderer-only.

---

## Identifiant

`F-142 / SF-142-20`

## Feature parente

`F-142` — Des diagrammes exacts dans les livrables (diagramme-as-code)

## Statut

`ready`

## Date de création

2026-09-30

## Branche Git

`feat/SF-142-20-svg-viewbox`

---

## Objectif

> En une phrase : faire en sorte que le SVG d'architecture cloud servi montre **tout** le schéma, net, une fois embarqué en `width:100%`, en rendant sa `viewBox` cohérente avec le contenu réellement dessiné.

---

## Comportement attendu

### Cas nominal

Le moteur `cloud` (`diagram-renderer/cloud.py`) construit le schéma avec graphviz à `dpi=144`
(SF-142-17, inchangé). Graphviz émet alors un SVG dont le groupe racine porte un `transform="scale(s …)"`
et dont la `viewBox` reste à l'échelle **1×**, alors que le contenu, une fois le `transform` appliqué,
occupe une étendue **différente** de cette `viewBox`. Comme `<svg>` a `overflow:hidden`, une `viewBox`
trop petite **rogne** le schéma (défaut de prod : seul le quart haut-gauche s'affiche), et une `viewBox`
trop grande le tasse dans un coin.

Après cette subfeature, juste après l'inlining des icônes (SF-142-18) et avant que le fichier ne soit
servi, un post-traitement réécrit **la seule `viewBox`** pour qu'elle englobe **exactement** le contenu
en espace utilisateur : `viewBox = viewBox_1× × scale` (le facteur du `transform` du groupe racine),
origine conservée. Le contenu remplit alors sa `viewBox` : plus de rognage, plus de tassement.

Rien d'autre ne bouge : `dpi` reste `"144"`, les attributs `width`/`height` (points, ×dpi/72) restent
tels quels — ils portent la mesure de densité (`svg_size`/`density_notice`, SF-142-19) —, le `transform`
et la géométrie ne sont pas touchés, les icônes restent inlinées en `data:` (SF-142-18).

### Cas d'erreur

| Situation | Comportement attendu |
|-----------|---------------------|
| Le SVG n'a pas de `viewBox` | Aucune réécriture, le fichier est laissé tel quel (jamais d'échec) |
| Le groupe racine n'a pas de `transform`/`scale` (ou `scale=1`) | `scale` traité comme 1 → réécriture **no-op** (idempotent), fichier inchangé |
| `scale(2)` (un seul nombre) ou `scale(2 2)` (deux nombres) | Les deux formes sont lues (sy = sx si absent) |
| Le fichier de sortie n'est pas du SVG (`outformat="png"`) | Aucun post-traitement viewBox (le PNG n'a pas de viewBox) |

---

## Critères d'acceptation

> Chaque critère est vérifiable.

- [ ] Sur un schéma **large** (chaîne LR, type acces-cluster), la `viewBox` servie **englobe exactement**
      la bounding box du contenu en espace utilisateur (la `viewBox` sert de cadre au contenu, sans rognage
      ni marge parasite) — au lieu d'en être un multiple (rapport ≠ 1 avant correctif).
- [ ] Un SVG **de forme production** (`scale(2 2)`, `viewBox` à 1×, `width`/`height` à 2×) voit sa `viewBox`
      réécrite à la valeur numérique de `width`/`height` (plus de rapport 2×).
- [ ] `GRAPH_ATTR["dpi"]` vaut toujours `"144"` (SF-142-17 inchangé).
- [ ] `svg_size` et `density_notice` rendent les **mêmes** valeurs qu'avant (SF-142-19 préservé) :
      `width`/`height` ne sont pas modifiés.
- [ ] Le SVG reste **auto-contenu** : les icônes officielles restent inlinées en `data:` URI (SF-142-18).
- [ ] Un post-traitement sur un SVG sans `viewBox` ou sans `scale` ne lève jamais et ne casse rien.
- [ ] Le test « large » **échoue avant** le correctif (rapport ≈2× entre viewBox et contenu) et **passe après**.

---

## Périmètre

### Hors scope (explicite)

- Toute modification de la mise en page graphviz (positions, `nodesep`/`ranksep`/`splines`, `dpi`) —
  ce correctif ne touche **que** la sérialisation `viewBox` du SVG servi.
- Toute modification du relais backend / des en-têtes HTTP (la note de densité transite déjà de bout en bout).
- Toute modification de la doctrine d'embarquement HTML (SF-142-19, consigne au modèle).
- Le redéploiement de l'image `diagram-renderer` (fait par la session principale hors de cette subfeature).

---

## Technique

### Composants impactés

| Fichier | Opération | Notes |
|---------|-----------|-------|
| `diagram-renderer/cloud.py` | Ajout `fit_viewbox()` + appel dans `build()` (branche SVG) | Post-traitement viewBox uniquement |
| `diagram-renderer/tests/test_cloud_svg.py` | Ajout de tests | Le test qui attrape le bug + tests unitaires de `fit_viewbox` |

Pas d'endpoint, pas de table, pas de migration, pas de composant Angular. Pas de préoccupation
transversale (Auth / tenant / plans / routing) : moteur de rendu isolé, sans accès aux données.

### Décision : piste retenue et justification

Deux pistes étaient possibles :

1. **Retirer/neutraliser le `dpi` pour la sortie SVG.** Rejetée : `dpi=144` est **load-bearing** ailleurs.
   Le test `test_le_dpi_ne_bouge_pas` (SF-142-17) exige `dpi=="144"` ; `svg_size` convertit les points en
   pixels-équivalents via `dpi/72` et alimente la note de densité **et** le critère « schéma large »
   (SF-142-19). Retirer le `dpi` changerait silencieusement ces mesures et régresserait SF-142-17/19.

2. **Post-traiter le SVG** en réécrivant la `viewBox`. **Retenue.** C'est chirurgical : on ne touche
   qu'à la sérialisation `viewBox`, jamais à la mise en page, jamais aux mesures de densité. Le défaut
   est un quirk connu de graphviz (viewBox laissée à 1× quand le `dpi` scale le reste) ; la réparation
   robuste est de rendre le SVG **cohérent avec lui-même** : `viewBox` = étendue réelle du contenu
   = `viewBox_1× × scale` (le facteur du `transform` racine). Cette formule vaut quel que soit le sens du
   quirk selon la version de graphviz (constaté : scale `0.5` en local graphviz 2.43 → viewBox trop
   grande ; `scale(2)` en prod → viewBox trop petite/rognage). Dans les deux cas, `viewBox ← viewBox × scale`
   fait coïncider le cadre et le contenu.

On **ne réécrit pas** `width`/`height` (contrairement à une lecture littérale de « viewBox == width »)
précisément pour ne pas perturber `svg_size`/`density_notice` (SF-142-19). Sur la forme de prod,
`viewBox × scale ≈ width` de toute façon : le critère « plus de rapport 2× » est atteint.

---

## Plan de test

### Tests unitaires (`diagram-renderer/tests/test_cloud_svg.py`, sans réseau)

- [ ] `fit_viewbox` sur un SVG **de forme production** (`scale(2 2)`, viewBox 1×, width/height 2×) →
      la viewBox devient égale (à l'unité près) à la valeur numérique de `width`/`height` : **plus de rapport 2×**.
- [ ] `fit_viewbox` sur un SVG sans `viewBox` → fichier inchangé, aucune exception.
- [ ] `fit_viewbox` sur un SVG sans `scale` (ou `scale=1`) → **no-op** (idempotent).
- [ ] `fit_viewbox` lit aussi `scale(s)` à un seul nombre.

### Test d'intégration — celui qui attrape le bug

- [ ] Rendu réel d'un schéma **large** (chaîne LR) : on mesure la bounding box du contenu en espace
      utilisateur (polygone de fond graphviz + `transform` racine) et on **assERTE que la `viewBox` servie
      l'englobe exactement** (largeur/hauteur de la viewBox == bbox du contenu, à l'unité près).
      Ce test **échoue sur le rendu non corrigé** (viewBox ≈ 2× off) et **passe** une fois `fit_viewbox` branché.

### Non-régression (doivent rester verts)

- [ ] `test_cloud_svg.py` — auto-contenu (SF-142-18).
- [ ] `test_cloud_placement.py` — densité, `svg_size`, « schéma large » (SF-142-19), `dpi=="144"` (SF-142-17).

### Isolation utilisateur

- [ ] Non applicable — le moteur de rendu ne lit aucune donnée utilisateur ni tenant (entrée = JSON de
      description sur stdin, sortie = fichier SVG). Aucun accès `user_id`.

---

## Dépendances

### Subfeatures bloquantes

- `SF-142-18` — done (le SVG et son inlining d'icônes existent ; c'est le défaut à corriger).
- `SF-142-19` — done (note de densité `svg_size`/`density_notice` à préserver).

### Questions ouvertes impactées

- Aucune (`docs/OPEN_QUESTIONS.md` non impacté).

---

## Notes et décisions

- Le correctif est **renderer-only** ; aucun ajustement backend nécessaire.
- **Déploiement** : comme SF-142-18/19, le correctif n'est actif en prod qu'après **redéploiement de
  l'image `diagram-renderer`** — hors périmètre de cette subfeature (session principale).
