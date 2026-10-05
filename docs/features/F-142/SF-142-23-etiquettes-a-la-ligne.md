# Mini-spec — [F-142 / SF-142-23] Les étiquettes de nœuds passent à la ligne

## Identifiant

`F-142 / SF-142-23`

## Feature parente

`F-142` — Diagrammes dans les livrables (moteur cloud `diagram-renderer/cloud.py`)

## Statut

`done`

## Date de création

2026-10-05

## Branche Git

`feat/SF-142-23-etiquettes-a-la-ligne`

---

## Objectif

Dans un schéma d'architecture cloud, un libellé de nœud plus large que son icône passe à la ligne au lieu de déborder sur l'étiquette du voisin.

---

## Comportement attendu

### Cas nominal

Défaut constaté (validé PO) : « Bastion AL2023 », « Lambda airflow-dag-trigger » et « Route 53 », côte à côte, se chevauchent. Cause : un nœud à icône a une taille fixe (1,4 pouce) ; graphviz espace les icônes, pas leurs libellés, et rien ne borne la largeur d'un libellé.

Remède, dans `cloud.py` : `wrap_label(texte, width=LABEL_WRAP)`, appliquée au libellé de chaque nœud **après** validation (`label_of`) :

1. **Borne : `LABEL_WRAP = 16` caractères par ligne.** 16 caractères à 13 pt font ~110 pt, sous l'écart entre deux icônes voisines (~122 pt : icône 1,4 po + `nodesep` 0,30 po).
2. On coupe d'abord sur les **espaces** (remplissage glouton).
3. Un mot plus long que la borne se coupe **après** un séparateur `-`, `_`, `.` ou `/`, qui reste en fin de ligne : `Lambda airflow-dag-trigger` → `Lambda airflow-` / `dag-trigger`.
4. Un fragment **sans séparateur** plus long que la borne reste **entier** (pas de coupe en plein mot : couper `managedworkflows…` fabriquerait un mot inexistant ; cas rare, `MAX_LABEL` borne déjà le total).
5. Un retour à la ligne écrit par l'auteur est respecté.
6. Un libellé court (≤ 16) est **inchangé**.
7. S'applique aux nœuds à icône **et** aux formes de repli `unknown_component` (y compris le repli sans libellé, qui porte le type : `aws.` / `managedworkflowsforapacheairflow`).

`diagrams.Node` ajoute déjà 0,4 po de hauteur par `\n` du libellé : la ligne supplémentaire ne mord pas sur l'icône.

### Cas d'erreur

| Situation | Comportement attendu | Code HTTP |
|-----------|---------------------|-----------|
| Libellé de nœud > `MAX_LABEL` (120) | Refus inchangé (« trop long ») — la validation porte sur le libellé **brut**, la coupe ne crée ni ne lève aucun refus | 400 (inchangé) |
| Fragment insécable > 16 caractères | Gardé entier sur sa propre ligne, pas d'erreur | 200 |
| Libellé vide | Inchangé (vide, ou type du nœud inconnu, passé à la ligne) | 200 |

---

## Critères d'acceptation

- [x] Un libellé ≤ 16 caractères est rendu à l'identique (« Route 53 », « Bastion AL2023 »).
- [x] « Lambda airflow-dag-trigger » devient « Lambda airflow- » / « dag-trigger ».
- [x] Aucune ligne produite ne dépasse 16 caractères, hors fragment insécable ; aucun caractère n'est perdu.
- [x] Le cas réel (les trois nœuds côte à côte) rendu en SVG : chaque ligne ≤ 16 et aucune étiquette n'empiète horizontalement sur sa voisine (largeurs mesurées avec DejaVu Sans, la police de graphviz).
- [x] Le repli `unknown_component` sans libellé passe aussi à la ligne.
- [x] Titres de cadres et étiquettes de liens **non** coupés.
- [x] `MAX_LABEL` vérifié sur le libellé brut.
- [x] Non-régression : SF-142-12/15/16/17/18-22 (suites `test_cloud*.py`) et export draw.io SF-142-13 (`test_drawio.js`) verts.

---

## Périmètre

### Hors scope (explicite)

- **Titres de cadres (groupes)** : ils s'étalent le long du cadre, qui s'élargit pour eux ; ils ne débordent sur personne.
- **Étiquettes de liens** : graphviz leur réserve leur place dans la mise en page ; les couper changerait la disposition sans corriger de chevauchement.
- **Export draw.io (SF-142-13)** : moteur distinct (`drawio.js`) qui reçoit la `spec` brute, jamais la sortie de `cloud.py` ; ses cellules ont déjà `whiteSpace=wrap` (draw.io passe lui-même à la ligne). Aucun `\n` n'y est donc injecté par cette SF — rien à faire passer.
- Coupe en plein mot avec trait d'union.
- Redéploiement du service `diagram-renderer` (fait par le PO).

---

## Contraintes de validation

| Champ | Obligatoire | Longueur max | Format / Valeurs autorisées | Unicité | Normalisation |
|-------|-------------|-------------|----------------------------|---------|---------------|
| `nodes[].label` | Non | 120 (brut, inchangé) | texte libre | Non | `strip()` puis coupe à 16 car./ligne |

---

## Technique

### Endpoint(s)

Aucun nouveau. `POST /` du service `diagram-renderer` (`engine=cloud`) : contrat inchangé, seul le rendu change.

### Tables impactées

Aucune.

### Migration Liquibase

- [x] Non applicable

### Composants

- `diagram-renderer/cloud.py` — `LABEL_WRAP`, `wrap_label()`, appel dans `build().place()` et `unknown_factory()`.
- Pas de composant Angular.

---

## Plan de test

### Tests unitaires (`diagram-renderer/tests/test_cloud.py`, classe `EtiquettesALaLigne`)

- [x] Libellé court intact.
- [x] Libellé long coupé sur ses séparateurs (cas réel Lambda).
- [x] Aucune ligne > borne, aucun caractère perdu (espaces, `_`, `.`, `/`).
- [x] Mot insécable > borne gardé entier.
- [x] Retour à la ligne de l'auteur respecté.

### Tests d'intégration (rendu réel graphviz)

- [x] Cas réel rendu en SVG : lignes ≤ 16, aucun chevauchement horizontal mesuré (le test échoue sur le code d'avant : « Bastion AL2023 » finit à x≈101, « Lambda airflow-dag-trigger » commence à x≈85).
- [x] Repli `unknown_component` sans libellé passé à la ligne.
- [x] Titre de cadre et étiquette de lien intacts.
- [x] Libellé brut > 120 toujours refusé.
- [x] Suites existantes : `test_cloud.py`, `test_cloud_groups.py`, `test_cloud_placement.py`, `test_cloud_svg.py`, `test_cloud_vocabulaire.py`, `test_drawio.js`.

### Isolation utilisateur

- [x] Non applicable — service de rendu sans état ni donnée utilisateur.

---

## Préoccupations transversales

Aucune (auth, tenant, plans/limites, navigation non touchés).

---

## Dépendances

### Subfeatures bloquantes

- SF-142-12, 15, 16, 17, 18, 19, 20, 21, 22 — done.

### Questions ouvertes impactées

- Aucune.

---

## Notes et décisions

- Borne 16 (bas de la fourchette 16-18) : c'est la plus grande qui garde deux voisins à la borne sans contact (~110 pt < ~122 pt).
- Fragment insécable gardé entier plutôt que coupé (lisibilité d'un identifiant > largeur stricte).
- `/` ajouté aux séparateurs (CIDR, chemins d'API).
- Test existant `test_sans_etiquette_la_forme_porte_le_type_demande` ajusté : le type est désormais porté sur deux lignes ; on vérifie qu'il est toujours là, entier (`\n` retirés).
